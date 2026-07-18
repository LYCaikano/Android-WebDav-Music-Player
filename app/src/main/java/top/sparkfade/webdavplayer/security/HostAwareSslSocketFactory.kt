package top.sparkfade.webdavplayer.security

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import top.sparkfade.webdavplayer.utils.CurrentSession

/**
 * 按目标主机动态选择 TLS 校验策略：
 * - 用户在账号配置中显式勾选 skipSsl 的主机 → 信任所有证书
 * - 其他主机 → 严格校验
 * OkHttp 建立 TLS 连接时总是调用带 host 参数的 createSocket(Socket, String, int, boolean)。
 */
class HostAwareSslSocketFactory(
    private val strictFactory: SSLSocketFactory,
    private val trustAllFactory: SSLSocketFactory
) : SSLSocketFactory() {

    private fun factoryFor(host: String?): SSLSocketFactory {
        return if (host != null && CurrentSession.isSkipSslHost(host)) trustAllFactory
        else strictFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = strictFactory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = strictFactory.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        return factoryFor(host).createSocket(socket, host, port, autoClose)
    }

    override fun createSocket(host: String, port: Int): Socket {
        return factoryFor(host).createSocket(host, port)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return factoryFor(host).createSocket(host, port, localHost, localPort)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return factoryFor(host.hostName).createSocket(host, port)
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        return factoryFor(address.hostName).createSocket(address, port, localAddress, localPort)
    }
}
