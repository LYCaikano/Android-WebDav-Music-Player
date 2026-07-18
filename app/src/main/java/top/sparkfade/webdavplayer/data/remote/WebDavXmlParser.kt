package top.sparkfade.webdavplayer.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLDecoder

data class WebDavResource(
    val href: String,
    val isCollection: Boolean,
    val contentLength: Long,
    val contentType: String,
    val displayName: String
)

class WebDavXmlParser {
    fun parse(inputStream: InputStream): List<WebDavResource> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(inputStream, null)
        parser.nextTag()
        return readMultiStatus(parser)
    }

    private fun readMultiStatus(parser: XmlPullParser): List<WebDavResource> {
        val list = mutableListOf<WebDavResource>()
        parser.require(XmlPullParser.START_TAG, null, "multistatus")
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG ->
                    if (parser.isLocalName("response")) list.add(readResponse(parser))
                    else skip(parser)
                XmlPullParser.END_TAG -> if (parser.isLocalName("multistatus")) return list
                XmlPullParser.END_DOCUMENT -> return list
            }
        }
    }

    private fun readResponse(parser: XmlPullParser): WebDavResource {
        var href = ""
        var isCollection = false
        var contentLength = 0L
        var contentType = ""
        var displayName = ""

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when {
                    parser.isLocalName("href") -> href = readText(parser)
                    parser.isLocalName("propstat") -> {
                        val result = readPropstat(parser)
                        if (result.contentLength > 0) contentLength = result.contentLength
                        if (result.contentType.isNotEmpty()) contentType = result.contentType
                        if (result.displayName.isNotEmpty()) displayName = result.displayName
                        if (result.isCollection) isCollection = true
                    }
                    else -> skip(parser)
                }
                XmlPullParser.END_TAG -> if (parser.isLocalName("response")) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        if (displayName.isEmpty()) {
            val rawName = href.trim('/').substringAfterLast('/')
            displayName = try {
                URLDecoder.decode(rawName, "UTF-8")
            } catch (e: Exception) {
                rawName
            }
        }

        return WebDavResource(href, isCollection, contentLength, contentType, displayName)
    }

    private data class Props(
        val contentLength: Long = 0,
        val contentType: String = "",
        val displayName: String = "",
        val isCollection: Boolean = false
    )

    private fun readPropstat(parser: XmlPullParser): Props {
        var contentLength = 0L
        var contentType = ""
        var displayName = ""
        var isCollection = false

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when {
                    parser.isLocalName("getcontentlength") ->
                        contentLength = readText(parser).toLongOrNull() ?: 0
                    parser.isLocalName("getcontenttype") -> contentType = readText(parser)
                    parser.isLocalName("displayname") -> displayName = readText(parser)
                    parser.isLocalName("resourcetype") ->
                        if (readInnerResourceType(parser)) isCollection = true
                    else -> skip(parser)
                }
                XmlPullParser.END_TAG -> if (parser.isLocalName("propstat")) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return Props(contentLength, contentType, displayName, isCollection)
    }

    private fun readInnerResourceType(parser: XmlPullParser): Boolean {
        var isCollection = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.isLocalName("collection")) isCollection = true
                    skip(parser)
                }
                XmlPullParser.END_TAG -> if (parser.isLocalName("resourcetype")) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return isCollection
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) throw IllegalStateException()
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun XmlPullParser.isLocalName(localName: String): Boolean =
        name.equals(localName, ignoreCase = false) || name.endsWith(":$localName")
}
