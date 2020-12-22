package io.iohk.scalanet.discovery.ethereum.v4

import cats.effect.Resource
import io.iohk.scalanet.discovery.crypto.{PublicKey, PrivateKey}
import io.iohk.scalanet.discovery.crypto.SigAlg
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.kademlia.KademliaIntegrationSpec
import io.iohk.scalanet.kademlia.XorOrdering
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.udp.StaticUDPPeerGroup
import java.net.InetSocketAddress
import monix.eval.Task
import scala.concurrent.duration._
import scodec.bits.BitVector

class DiscoveryKademliaIntegrationSpec extends KademliaIntegrationSpec("DiscoveryService with StaticUDPPeerGroup") {
  override type PeerRecord = Node

  class DiscoveryTestNode(
      override val self: Node,
      service: DiscoveryService
  ) extends TestNode {
    override def getPeers: Task[Seq[Node]] =
      service.getNodes.map(_.toSeq)
  }

  // Using fake crypto and scodec encoding instead of RLP.
  implicit val sigalg: SigAlg = new MockSigAlg()
  import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs._
  // Not dealing with non-conforming clients here.
  implicit val packetCoded = Packet.packetCodec(allowDecodeOverMaxPacketSize = false)

  override def generatePeerRecordWithKey = {
    val address = NetUtils.aRandomAddress()
    val (publicKey, privateKey) = sigalg.newKeyPair
    val node = Node(publicKey, Node.Address(address.getAddress, address.getPort, address.getPort))
    node -> privateKey
  }

  override def makeXorOrdering(nodeId: BitVector): Ordering[Node] =
    XorOrdering[Node, Hash](_.kademliaId)(Node.kademliaId(PublicKey(nodeId)))

  override def startNode(
      selfRecordWithKey: (Node, PrivateKey),
      initialNodes: Set[Node],
      testConfig: TestNodeKademliaConfig
  ): Resource[Task, TestNode] = {
    val (selfNode, privateKey) = selfRecordWithKey
    for {
      peerGroup <- StaticUDPPeerGroup[Packet](
        StaticUDPPeerGroup.Config(
          bindAddress = nodeAddressToInetMultiAddress(selfNode.address).inetSocketAddress,
          receiveBufferSizeBytes = Packet.MaxPacketBitsSize / 8 * 2
        )
      )
      config = DiscoveryConfig.default.copy(
        requestTimeout = 500.millis,
        kademliaTimeout = 100.millis, // We won't get that many results and waiting for them is slow.
        kademliaAlpha = testConfig.alpha,
        kademliaBucketSize = testConfig.k,
        discoveryPeriod = testConfig.refreshRate,
        knownPeers = initialNodes,
        subnetLimitPrefixLength = 0
      )
      network <- Resource.liftF {
        DiscoveryNetwork[InetMultiAddress](
          peerGroup,
          privateKey,
          localNodeAddress = selfNode.address,
          toNodeAddress = inetMultiAddressToNodeAddress,
          config = config
        )
      }
      service <- DiscoveryService[InetMultiAddress](
        privateKey,
        node = selfNode,
        config = config,
        network = network,
        toAddress = nodeAddressToInetMultiAddress
      )
    } yield new DiscoveryTestNode(selfNode, service)
  }

  def inetMultiAddressToNodeAddress(address: InetMultiAddress): Node.Address = {
    val addr = address.inetSocketAddress
    Node.Address(addr.getAddress, addr.getPort, addr.getPort)
  }

  def nodeAddressToInetMultiAddress(address: Node.Address): InetMultiAddress =
    InetMultiAddress(new InetSocketAddress(address.ip, address.udpPort))
}
