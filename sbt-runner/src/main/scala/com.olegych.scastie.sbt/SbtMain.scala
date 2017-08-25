package com.olegych.scastie.sbt

import com.olegych.scastie.util.ScastieFileUtil.writeRunningPid
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import com.olegych.scastie.ReconnectInfo
import org.slf4j.LoggerFactory

object SbtMain {
  def main(args: Array[String]): Unit = {
    val logger = LoggerFactory.getLogger(getClass)

    val system = ActorSystem("SbtRunner")

    val config2 = ConfigFactory.load().getConfig("akka.remote.netty.tcp")
    logger.info("akka tcp config")
    logger.info("  '" + config2.getString("hostname") + "'")
    logger.info("  " + config2.getInt("port"))

    val config = ConfigFactory.load().getConfig("com.olegych.scastie")

    val serverConfig = config.getConfig("web")
    val sbtConfig = config.getConfig("sbt")

    val isProduction = sbtConfig.getBoolean("production")

    val isReconnecting = sbtConfig.getBoolean("reconnect")

    if (isProduction) {
      val pid = writeRunningPid()
      logger.info(s"Starting sbtRunner pid: $pid")
    }

    val timeout = {
      val timeunit = TimeUnit.SECONDS
      FiniteDuration(
        sbtConfig.getDuration("runTimeout", timeunit),
        timeunit
      )
    }

    val reconnectInfo =
      ReconnectInfo(
        serverHostname = serverConfig.getString("hostname"),
        serverAkkaPort = serverConfig.getInt("akka-port"),
        actorHostname = sbtConfig.getString("hostname"),
        actorAkkaPort = sbtConfig.getInt("akka-port")
      )

    logger.info("  timeout: {}", timeout)
    logger.info("  isProduction: {}", isProduction)
    logger.info("  runner hostname: {}", reconnectInfo.actorHostname)
    logger.info("  runner port: {}", reconnectInfo.actorAkkaPort)
    logger.info("  server hostname: {}", reconnectInfo.serverHostname)
    logger.info("  server port: {}", reconnectInfo.serverAkkaPort)

    system.actorOf(
      Props(
        new SbtActor(
          system = system,
          runTimeout = timeout,
          production = isProduction,
          readyRef = None,
          reconnectInfo = Some(reconnectInfo)
        )
      ),
      name = "SbtActor"
    )

    Await.result(system.whenTerminated, Duration.Inf)

    ()
  }
}
