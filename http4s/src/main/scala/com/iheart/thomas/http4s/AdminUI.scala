package com.iheart.thomas
package http4s

import com.iheart.thomas.http4s.abtest.AbtestManagementUI
import com.iheart.thomas.http4s.auth.{AuthDependencies, AuthedEndpointsUtils, AuthenticationAlg, Token, UI}
import org.http4s.dsl.Http4sDsl
import cats.implicits._
import com.iheart.thomas.dynamo
import cats.effect._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.iheart.thomas.admin.{Role, User}
import com.typesafe.config.Config
import org.http4s.Response
import org.http4s.server.{Router, ServiceErrorHandler}
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.module.catseffect.syntax._
import cats.MonadThrow
import com.iheart.thomas.http4s.AdminUI.AdminUIConfig
import com.iheart.thomas.kafka.JsonMessageSubscriber
import com.iheart.thomas.stream.JobAlg
import org.typelevel.log4cats.Logger
import fs2.Stream

import scala.concurrent.ExecutionContext
import org.http4s.twirl._
import tsec.authentication.Authenticator
import tsec.passwordhashers.jca.BCrypt
import org.typelevel.log4cats.slf4j.Slf4jLogger
import ThrowableExtension._
import com.iheart.thomas.abtest.PerformantAssigner
import com.iheart.thomas.stream.ArmParser.JValueArmParser
import com.iheart.thomas.analysis.AccumulativeKPIQueryRepo
import com.iheart.thomas.stream.TimeStampParser.JValueTimeStampParser
import com.iheart.thomas.stream.UserParser.JValueUserParser
import com.iheart.thomas.tracking.EventLogger
import org.http4s.blaze.server.BlazeServerBuilder

class AdminUI[F[_]: MonadThrow](
    abtestManagementUI: AbtestManagementUI[F],
    authUI: auth.UI[F, AuthImp],
    analysisUI: analysis.UI[F],
    streamUI: stream.UI[F],
    banditUI: bandit.UI[F]
  )(implicit adminUICfg: AdminUIConfig,
    jobAlg: JobAlg[F],
    authenticator: Authenticator[F, String, User, Token[AuthImp]])
    extends AuthedEndpointsUtils[F, AuthImp]
    with Http4sDsl[F] {

  val routes = authUI.publicEndpoints <+> liftService(
    abtestManagementUI.routes <+> authUI.authedService <+> analysisUI.routes <+> streamUI.routes <+> banditUI.routes
  )
  def gateway = abtestManagementUI.gateway

  val serverErrorHandler: ServiceErrorHandler[F] = { _ =>
    {
      case admin.Authorization.LackPermission =>
        Response[F](Unauthorized).pure[F]
      case e =>
        InternalServerError(
          html.errorMsg(
            s"""Ooops! something bad happened.
        
              ${e.fullStackTrace}
            """
          )
        )
    }
  }

  def backgroundProcess: Stream[F, Unit] = jobAlg.runStream

}

object AdminUI {

  case class AdminUIConfig(
      key: String,
      rootPath: String,
      adminTablesReadCapacity: Long,
      adminTablesWriteCapacity: Long,
      initialAdminUsername: String,
      initialRole: Role,
      siteName: String,
      docUrl: String)

  implicit val roleCfgReader: ConfigReader[Role] =
    ConfigReader.fromNonEmptyString(s =>
      auth.Roles
        .fromRepr(s)
        .leftMap(_ => CannotConvert(s, "Role", "Invalid value"): FailureReason)
    )

  def loadConfig[F[_]: Sync](cfg: Config): F[AdminUIConfig] = {
    import pureconfig.generic.auto._
    ConfigSource.fromConfig(cfg).at("thomas.admin-ui").loadF[F, AdminUIConfig]()
  }

  def resource[
      F[_]: Async: Logger: EventLogger
        : AccumulativeKPIQueryRepo: JValueArmParser: JValueTimeStampParser
        : JValueUserParser
    ](implicit dc: DynamoDbAsyncClient,
      cfg: AdminUIConfig,
      config: Config,
      ec: ExecutionContext
    ): Resource[F, AdminUI[F]] = {

    Resource.eval(
      dynamo.AdminDAOs.ensureAuthTables[F](
        cfg.adminTablesReadCapacity,
        cfg.adminTablesWriteCapacity
      )
    ) *>
      Resource.eval(
        dynamo.BanditsDAOs.ensureBanditTables[F](
          cfg.adminTablesReadCapacity,
          cfg.adminTablesWriteCapacity
        )
      ) *>
      Resource.eval(
        dynamo.AnalysisDAOs.ensureAnalysisTables[F](
          cfg.adminTablesReadCapacity,
          cfg.adminTablesWriteCapacity
        )
      ) *> {
        Resource.eval(AuthDependencies[F](cfg.key)).flatMap { deps =>
          import deps._
          import dynamo.AdminDAOs._
          import dynamo.BanditsDAOs._
          implicit val authAlg = AuthenticationAlg[F, BCrypt, AuthImp]
          val authUI = new UI(Some(cfg.initialAdminUsername), cfg.initialRole)
          import dynamo.AnalysisDAOs._
          import JsonMessageSubscriber._

          AbtestManagementUI
            .fromMongo[F](config)
            .flatMap { amUI =>
              implicit val abtestAlg = amUI.alg

              com.iheart.thomas.bandit.bayesian.BayesianMABAlg.apply[F]
              PerformantAssigner.resource[F].map { implicit assigner =>
                new AdminUI(
                  amUI,
                  authUI,
                  new analysis.UI,
                  new stream.UI,
                  new bandit.UI
                )
              }
            }
        }
      }
  }

  def resourceFromDynamo[
      F[_]: Async: Logger: EventLogger
        : AccumulativeKPIQueryRepo: JValueArmParser: JValueTimeStampParser
        : JValueUserParser
    ](implicit
      cfg: Config,
      adminUIConfig: AdminUIConfig,
      ec: ExecutionContext
    ): Resource[F, AdminUI[F]] =
    dynamo
      .client(ConfigSource.fromConfig(cfg).at("thomas.admin-ui.dynamo"))
      .flatMap { implicit dc => resource }

  /** Provides a server that serves the Admin UI
    */
  def serverResourceAutoLoadConfig[
      F[_]: Async: EventLogger
        : AccumulativeKPIQueryRepo
        : JValueArmParser: JValueTimeStampParser: JValueUserParser
    ](implicit dc: DynamoDbAsyncClient,
      executionContext: ExecutionContext
    ): Resource[F, ExitCode] = {
    ConfigResource.cfg[F]().flatMap { implicit c =>
      Resource.eval(loadConfig[F](c)).flatMap { implicit cfg =>
        serverResource[F]
      }
    }
  }

  /** Provides a server that serves the Admin UI
    */
  def serverResource[
      F[_]: Async: EventLogger
        : AccumulativeKPIQueryRepo: JValueArmParser: JValueTimeStampParser
        : JValueUserParser
    ](implicit
      adminCfg: AdminUIConfig,
      config: Config,
      dc: DynamoDbAsyncClient,
      executionContext: ExecutionContext
    ): Resource[F, ExitCode] = {
    import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
    Resource.eval(Slf4jLogger.create[F]).flatMap { implicit logger =>
      for {
        ui <- AdminUI.resource[F]
        e <-
          BlazeServerBuilder[F]
            .bindHttp(8080, "0.0.0.0")
            .withHttpApp(
              Router(adminCfg.rootPath -> ui.routes,
                "" -> ui.gateway).orNotFound
            )
            .withServiceErrorHandler(ui.serverErrorHandler)
            .serve
            .concurrently(ui.backgroundProcess)
            .compile
            .resource
            .lastOrError

      } yield e
    }
  }
}
