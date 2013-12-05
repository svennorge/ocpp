package com.thenewmotion.ocpp.spray

import xml.{XML, NodeSeq}
import com.typesafe.scalalogging.slf4j.Logging
import soapenvelope12.{Body, Fault, Envelope}
import com.thenewmotion.ocpp._
import com.thenewmotion.ocpp.Fault._
import _root_.spray.http.{StatusCodes, HttpResponse, HttpRequest}
import StatusCodes._
import java.io.ByteArrayInputStream
import scala.language.implicitConversions

/**
 * The information about the charge point available in an incoming request
 */
case class ChargerInfo(ocppVersion: Version.Value, endpointUrl: Option[Uri], chargerId: String)

object OcppProcessing extends Logging {

  type ResponseFunc[T] = Option[T] => HttpResponse
  type ChargerId = String
  type Result[T] = Either[HttpResponse, (ChargerInfo, ResponseFunc[T])]
  type OcppMessageLogger = (ChargerId, Version.Value, Any) => Unit

  def apply[T: OcppService](req: HttpRequest): Result[T] = {
    val (decoded, encode) = decodeEncode(req)
    applyDecoded(decoded) match {
      case Right((id, f)) => Right((id, (x: Option[T]) => encode(f(x))))
      case Left(res) => Left(encode(res))
    }
  }

  private[spray] def applyDecoded[T: OcppService](req: HttpRequest): Result[T] = safe {
    for {
      post <- soapPost(req).right
      xml <- toXml(post).right
      env <- envelope(xml).right
      chargerId <- chargerId(env).right
      version <- parseVersion(env.Body).right
      chargerInfo <- Right(ChargerInfo(version, ChargeBoxAddress.unapply(env), chargerId)).right
    } yield {

      val f: ResponseFunc[T] = {
        case None =>
          val msg = s"Charge box ${chargerInfo.chargerId} not found"
          logger.warn(msg)
          OcppResponse(IdentityMismatch(msg))

        case Some(service) =>
          httpLogger.debug(s">>\n\t${req.headers.mkString("\n\t")}\n\t$xml")
          safe(OcppResponse(dispatch(chargerInfo.ocppVersion, env.Body, service))).merge
      }

      chargerInfo -> f
    }
  }.joinRight

  private def parseVersion(body: Body): Either[HttpResponse, Version.Value] = {
    Version.fromBody(body) match {
      case Some(version) => Right(version)
      case None => ProtocolError("Can't find an ocpp version")
    }
  }

  private def safe[T](func: => T): Either[HttpResponse, T] = try Right(func) catch {
    case e: Exception =>
      logger.error(e.getMessage, e)
      InternalError(e.getMessage)
  }

  private def soapPost(req: HttpRequest): Either[HttpResponse, HttpRequest] =
    SoapPost.unapply(req) match {
      case None => Left(HttpResponse(NotFound))
      case _ => Right(req)
    }

  private def toXml(req: HttpRequest): Either[HttpResponse, NodeSeq] = {
    try XML.load(new ByteArrayInputStream(req.entity.data.toByteArray)) match {
      case NodeSeq.Empty => ProtocolError("Body is empty")
      case xml =>
        logger.debug(">> " + xml.toString())
        Right(xml)
    } catch {
      case e: Exception =>
        logger.error(e.getMessage)
        ProtocolError(e.getMessage)
    }
  }

  private def envelope(xml: NodeSeq): Either[HttpResponse, Envelope] =
    scalaxb.fromXMLEither[Envelope](xml).left.map(x => OcppResponse(ProtocolError(x)))

  private def chargerId(env: Envelope): Either[HttpResponse, ChargerId] =
    ChargeBoxIdentity.unapply(env).toRight {
      val msg = "Failed to parse 'chargeBoxIdentity' value"
      logger.warn(msg)
      OcppResponse(ProtocolError(msg))
    }

  private[spray] def dispatch[T](version: Version.Value, body: Body, service: => T)
                                (implicit ocppService: OcppService[T]): Body =
    ocppService(version).dispatch(body, service)

  implicit def errorToEither[T](x: Fault): Either[HttpResponse, T] = Left(OcppResponse(x))

  def decodeEncode(request: HttpRequest): (HttpRequest, (HttpResponse => HttpResponse)) = {
    import _root_.spray.httpx.encoding.{Gzip, Deflate, NoEncoding}

    val encoder = request.headers.collectFirst {
      case header if header is "content-encoding" => header.value
    } match {
      case Some("deflate") => Deflate
      case Some("gzip") => Gzip
      case _ => NoEncoding
    }

    val decoded = encoder.decode(request)
    decoded -> encoder.encode[HttpResponse]
  }
}

