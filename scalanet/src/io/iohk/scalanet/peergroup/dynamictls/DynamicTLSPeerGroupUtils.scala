package io.iohk.scalanet.peergroup.dynamictls

import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate

import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import javax.net.ssl._
import scodec.bits.BitVector

import java.util.Arrays

private[scalanet] object DynamicTLSPeerGroupUtils {
  // supporting only ciphers which are used in TLS 1.3
  val supportedCipherSuites: java.lang.Iterable[String] = Arrays.asList(
    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
  )

  // key for peerId passed in Handshake session, used in sslEngine
  val peerIdKey = "peerId"

  /**
    *
    * Custom manager which is used by netty ssl to accept or reject peer certificates.
    *
    * Extended version is needed to have access to SslEngine to, to pass client id to other parts of the system
    * via getSSLParameters
    *
    * Methods without SslEngine argument are left with `???` to make sure that if there would arise case that they would
    * be called, then exception will be thrown instead of just trusting external peer without validations.
    *
    */
  class DynamicTlsTrustManager(info: Option[BitVector]) extends X509ExtendedTrustManager {
    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ???

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ???

    override def getAcceptedIssuers: Array[X509Certificate] = {
      new Array[X509Certificate](0)
    }

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String, socket: Socket): Unit = ???

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {
      CustomTlsValidator.validateCertificates(x509Certificates, info) match {
        case Left(er) => throw er
        case Right(value) =>
          val id = value.publicKey.getNodeId
          sslEngine.getHandshakeSession.putValue(peerIdKey, id)
      }
    }

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String, socket: Socket): Unit = ???

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {
      CustomTlsValidator.validateCertificates(x509Certificates, info) match {
        case Left(er) => throw er
        case Right(_) => ()
      }
    }
  }

  class CustomTrustManagerFactory(info: Option[BitVector]) extends SimpleTrustManagerFactory {

    private val tm = new DynamicTlsTrustManager(info)

    override def engineGetTrustManagers(): Array[TrustManager] = {
      Array[TrustManager] { tm }
    }

    override def engineInit(keyStore: KeyStore): Unit = {}

    override def engineInit(managerFactoryParameters: ManagerFactoryParameters): Unit = {}
  }

  sealed trait SSLContextFor
  case object SSLContextForServer extends SSLContextFor
  case class SSLContextForClient(to: PeerInfo) extends SSLContextFor

  def buildCustomSSlContext(f: SSLContextFor, config: DynamicTLSPeerGroup.Config): SslContext = {
    f match {
      case SSLContextForServer =>
        SslContextBuilder
          .forServer(config.connectionKeyPair.getPrivate, List(config.connectionCertificate): _*)
          .trustManager(new CustomTrustManagerFactory(None))
          .clientAuth(ClientAuth.REQUIRE)
          .ciphers(supportedCipherSuites)
          .protocols("TLSv1.2")
          .build()

      case SSLContextForClient(info) =>
        SslContextBuilder
          .forClient()
          .keyManager(config.connectionKeyPair.getPrivate, List(config.connectionCertificate): _*)
          .trustManager(new CustomTrustManagerFactory(Some(info.id)))
          .ciphers(supportedCipherSuites)
          .protocols("TLSv1.2")
          .build()
    }
  }

}
