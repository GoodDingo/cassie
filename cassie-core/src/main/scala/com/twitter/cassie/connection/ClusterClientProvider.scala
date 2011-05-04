package com.twitter.cassie.connection

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.apache.cassandra.finagle.thrift.Cassandra.ServiceToClient
import org.apache.thrift.protocol.TBinaryProtocol
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.Protocol
import com.twitter.finagle.thrift.{ThriftClientRequest, ThriftClientFramedCodec}
import com.twitter.util.Duration
import com.twitter.util.Future
import com.twitter.finagle.stats.OstrichStatsReceiver
import com.twitter.finagle.Codec

/**
 * Manages connections to the nodes in a Cassandra cluster.
 *
 * @param retryAttempts the number of times a query should be attempted before
 *                      throwing an exception
 * @param requestTimeoutInMS the amount of time, in milliseconds, the client will
 *                        wait for a response from the server before considering
 *                        the query to have failed
 * @param minConnectionsPerHost the minimum number of connections to maintain to
 *                              the node
 * @param maxConnectionsPerHost the maximum number of connections to maintain to
 *                              the ndoe
 * @param removeAfterIdleForMS the amount of time, in milliseconds, after which
 *                             idle connections should be closed and removed
 *                             from the pool
 * @param framed true if the server will only accept framed connections
 */

private[cassie] class ClusterClientProvider(val hosts: CCluster,
                            val keyspace: String,
                            val retryAttempts: Int = 5,
                            val requestTimeoutInMS: Int = 10000,
                            val connectionTimeoutInMS: Int = 10000,
                            val minConnectionsPerHost: Int = 1,
                            val maxConnectionsPerHost: Int = 5,
                            val removeAfterIdleForMS: Int = 60000) extends ClientProvider {

  private val service = ClientBuilder()
      .cluster(hosts)
      .protocol(CassandraProtocol(keyspace))
      .retries(retryAttempts)
      .requestTimeout(Duration(requestTimeoutInMS, TimeUnit.MILLISECONDS))
      .connectionTimeout(Duration(connectionTimeoutInMS, TimeUnit.MILLISECONDS))
      .hostConnectionCoresize(minConnectionsPerHost)
      .hostConnectionLimit(maxConnectionsPerHost)
      .reportTo(new OstrichStatsReceiver) //TODO make this configurable
      .hostConnectionIdleTime(Duration(removeAfterIdleForMS, TimeUnit.MILLISECONDS))
      .build()

  private val client = new ServiceToClient(service, new TBinaryProtocol.Factory())

  def map[A](f: ServiceToClient => Future[A]) = f(client)

  override def close(): Unit = {
    hosts.close
    service.release()
    ()
  }

  class ThriftFramedCodec extends Codec[ThriftClientRequest, Array[Byte]] {
    override def clientCodec = ThriftClientFramedCodec()
  }

  case class CassandraProtocol(keyspace: String) extends Protocol[ThriftClientRequest, Array[Byte]]
  {
    def codec = new ThriftFramedCodec()
    override def prepareChannel(cs: Service[ThriftClientRequest, Array[Byte]]) = {
      // perform connection setup
      val client = new ServiceToClient(cs, new TBinaryProtocol.Factory())
      client.set_keyspace(keyspace).map{ _ => cs }
    }
  }
}
