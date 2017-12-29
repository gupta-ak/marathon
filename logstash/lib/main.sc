#!/usr/bin/env amm

import $file.dependencies
import $file.util
import $file.logformat

import logformat.LogFormat
import scala.annotation.tailrec
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.IOResult
import akka.util.ByteString
import akka.stream.scaladsl._
import ammonite.ops._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Try, Success, Failure}

@tailrec def await[T](f: Future[T]): T = f.value match {
  case None =>
    Thread.sleep(10)
    await(f)
  case Some(v) =>
    v.get
}

def renderTemplate(template: Path, vars: (String, String)*): String =
  vars.foldLeft(read!(template)) { case (str, (env, value)) =>
    str.replace(s"%${env}%", value)
  }

def gzipSource(input: Path, maxChunkSize: Int = 1024): Source[ByteString, Future[IOResult]] = {
  FileIO.fromPath(input.toNIO).via(Compression.gunzip(maxChunkSize))
}

def bundleLogGzipped(masterPath: Path) =
  masterPath / "dcos-marathon.service.gz"

def bundleLogGunzipped(masterPath: Path) =
  masterPath / "dcos-marathon.service"

def warningLineSplitter(file: Path, warnLength: Int) =
  Framing.delimiter(ByteString("\n"), 128000000, true).map { line =>
    if (line.length >= warnLength)
      println(s"WARNING!!! ${file} has a line length of ${line.length}")
    line
  }

def unzipAndStripLogs(masters: Map[String, Path])(implicit mat: Materializer): Map[String, Path] = {
  masters.map { case (master, masterPath) =>
    val gunzippedFilePath = bundleLogGunzipped(masterPath)
    if (!gunzippedFilePath.toIO.exists) {

      val gzippedFilePath = bundleLogGzipped(masterPath)
      println(s"Extracting ${gzippedFilePath} to ${gunzippedFilePath}")
      val result = await {
        gzipSource(gzippedFilePath)
          .via(warningLineSplitter(gzippedFilePath, 128000))
          .map { bytes => ByteString(util.stripAnsi(bytes.utf8String)) }
          .runWith(FileIO.toPath(gunzippedFilePath.toNIO))
      }
      if (!result.wasSuccessful) {
        println(s"WARNING! Error extracting ${gzippedFilePath}; ${result.status}. ${result.count} bytes were written.")
      }
    }
    master -> gunzippedFilePath
  }
}

def detectLogFormat(logFiles: Seq[Path])(implicit mat: Materializer): LogFormat = {
  logFiles
    .filter(_.toIO.exists)
    .toSeq
    .sortBy(_.toIO.length)
    .reverse
    .toStream
    .map { input =>
      val result = Try {
        val linesSample = await(
          FileIO.fromPath(input.toNIO)
            .via(warningLineSplitter(input, 128000))
            .take(100)
            .runWith(Sink.seq))

        val maybeCodec = (for {
          line <- linesSample.take(100)
          codec <- LogFormat.all if codec.matches(line.utf8String)
        } yield codec).headOption

        maybeCodec match {
          case Some(codec) => codec
          case _ =>
            println(s"Couldn't find a codec for these lines:")
            println()
            linesSample.foreach(println)
            // sys.exit(1)
            ???
        }
      }
      input -> result
    }
    .flatMap {
      case (_, Success(result)) => Some(result)
      case (input, Failure(ex)) =>
        println(s"Failed to detect format in ${input}; ${ex}")
        None
    }
    .headOption
    .getOrElse {
      throw new Exception("Couldn't detect log format in any input files")
    }
}

def setupTarget(target: Path): (Path, Path, Path) = {
  val loading = target / 'loading
  val printing = target / 'printing
  rm(target)
  Seq(target,loading,printing).foreach(mkdir!(_))
  (target, printing, loading)
}

def generateTargetBundle(path: Path): Unit = {
  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  println(path)

  val entries = ls!(path)
  val masterPaths = entries.filter(_.last.endsWith("_master"))
  val masters = masterPaths.map { path => path.last.takeWhile(_ != '_') -> path }.toMap

  println(s"${masters.size} masters discovered: ${masters.keys.mkString(", ")}")

  val (target, printing, loading) = setupTarget(pwd / 'target)

  val unzippedLogLocations = unzipAndStripLogs(masters)

  val logFormat = detectLogFormat(unzippedLogLocations.values.toSeq)

  // Write out the debug template set
  val tcpReader = renderTemplate(
    pwd / "conf" / "input-tcp.conf.template",
    "CODEC" -> logFormat.codec)

  write.over(printing / "10-input.conf", tcpReader)
  write.over(printing / "15-filters-format.conf", logFormat.unframe)
  write.over(printing / "20-filters.conf", read!(pwd / "conf" / "dcos-marathon-1.4.x-filters.conf"))
  write.over(printing / "30-output.conf", read!(pwd / "conf" / "output-console.conf"))

  unzippedLogLocations.foreach { case (master, logPath) =>
    val inputConf = renderTemplate(
      pwd / "conf" / "input-file.conf.template",
      "FILE" -> util.escapeString(logPath.toString),
      "SINCEDB" -> util.escapeString((loading / s"since-db-${master}.db").toString),
      "CODEC" -> logFormat.codec,
      "EXTRA" -> s"""|"add_field" => {
                     |  "hostname" => ${util.escapeString(master)}
                     |}
                     |""".stripMargin
    )
    write.over(loading / s"10-input-${master}.conf", inputConf)
  }

  write.over(loading / "15-filters-format.conf", logFormat.unframe)
  write.over(loading / "20-filters.conf", read!(pwd / "conf" / "dcos-marathon-1.4.x-filters.conf"))
  write.over(loading / "30-output.conf", read!(pwd / "conf" / "output-elasticsearch.conf"))

  println(s"All Done")
}

@main def config(kind: String, path: Path): Unit = {
  kind match {
    case "bundle" =>
      generateTargetBundle(path)
  }
}
