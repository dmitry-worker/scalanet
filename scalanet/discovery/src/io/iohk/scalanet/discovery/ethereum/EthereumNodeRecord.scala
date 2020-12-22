package io.iohk.scalanet.discovery.ethereum

import scodec.bits.ByteVector
import scala.collection.SortedMap
import scala.math.Ordering.Implicits._
import java.nio.charset.StandardCharsets
import io.iohk.scalanet.discovery.crypto.{Signature, PrivateKey, SigAlg}
import scodec.{Codec, Attempt}
import io.iohk.scalanet.discovery.crypto.PublicKey
import java.net.Inet6Address

/** ENR corresponding to https://github.com/ethereum/devp2p/blob/master/enr.md */
case class EthereumNodeRecord(
    // Signature over the record contents: [seq, k0, v0, k1, v1, ...]
    signature: Signature,
    content: EthereumNodeRecord.Content
)

object EthereumNodeRecord {

  implicit val byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Iterable[Byte]](_.toIterable)

  case class Content(
      // Nodes should increment this number whenever their properties change, like their address, and re-publish.
      seq: Long,
      // Normally clients treat the values as RLP, however we don't have access to the RLP types here, hence it's just bytes.
      attrs: SortedMap[ByteVector, ByteVector]
  )
  object Content {
    def apply(seq: Long, attrs: (ByteVector, ByteVector)*): Content =
      Content(seq, SortedMap(attrs: _*))
  }

  object Keys {
    private def key(k: String): ByteVector =
      ByteVector(k.getBytes(StandardCharsets.UTF_8))

    /** name of identity scheme, e.g. "v4" */
    val id = key("id")

    /** compressed secp256k1 public key, 33 bytes */
    val secp256k1 = key("secp256k1")

    /** IPv4 address, 4 bytes */
    val ip = key("ip")

    /** TCP port, big endian integer */
    val tcp = key("tcp")

    /** UDP port, big endian integer */
    val udp = key("udp")

    /** IPv6 address, 16 bytes */
    val ip6 = key("ip6")

    /** IPv6-specific TCP port, big endian integer */
    val tcp6 = key("tcp6")

    /** IPv6-specific UDP port, big endian integer */
    val udp6 = key("udp6")

    /** The keys above have pre-defined meaning, but there can be arbitrary entries in the map. */
    val Predefined: Set[ByteVector] = Set(id, secp256k1, ip, tcp, udp, ip6, tcp6, udp6)
  }

  def apply(signature: Signature, seq: Long, attrs: (ByteVector, ByteVector)*): EthereumNodeRecord =
    EthereumNodeRecord(
      signature,
      EthereumNodeRecord.Content(seq, attrs: _*)
    )

  def apply(privateKey: PrivateKey, seq: Long, attrs: (ByteVector, ByteVector)*)(
      implicit sigalg: SigAlg,
      codec: Codec[Content]
  ): Attempt[EthereumNodeRecord] = {
    val content = EthereumNodeRecord.Content(seq, attrs: _*)
    codec.encode(content).map { data =>
      val sig = sigalg.removeRecoveryId(sigalg.sign(privateKey, data))
      EthereumNodeRecord(sig, content)
    }
  }

  def fromNode(node: Node, privateKey: PrivateKey, seq: Long)(
      implicit sigalg: SigAlg,
      codec: Codec[Content]
  ): Attempt[EthereumNodeRecord] = {
    val (ipKey, tcpKey, udpKey) =
      if (node.address.ip.isInstanceOf[Inet6Address])
        (Keys.ip6, Keys.tcp6, Keys.udp6)
      else
        (Keys.ip, Keys.tcp, Keys.udp)

    val attrs = List(
      Keys.id -> ByteVector("v4".getBytes(StandardCharsets.UTF_8)),
      Keys.secp256k1 -> sigalg.compressPublicKey(sigalg.toPublicKey(privateKey)).toByteVector,
      ipKey -> ByteVector(node.address.ip.getAddress),
      tcpKey -> ByteVector.fromInt(node.address.tcpPort),
      udpKey -> ByteVector.fromInt(node.address.udpPort)
    )

    apply(privateKey, seq, attrs: _*)
  }

  def validateSignature(
      enr: EthereumNodeRecord,
      publicKey: PublicKey
  )(implicit sigalg: SigAlg, codec: Codec[Content]): Attempt[Boolean] = {
    codec.encode(enr.content).map { data =>
      sigalg.verify(publicKey, enr.signature, data)
    }
  }
}
