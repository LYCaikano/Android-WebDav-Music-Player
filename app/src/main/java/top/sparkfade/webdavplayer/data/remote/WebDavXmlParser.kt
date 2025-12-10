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
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            // 匹配 response 标签
            if (parser.name.contains("response")) {
                list.add(readResponse(parser))
            } else {
                skip(parser)
            }
        }
        return list
    }

    private fun readResponse(parser: XmlPullParser): WebDavResource {
        var href = ""
        var isCollection = false
        var contentLength = 0L
        var contentType = ""
        var displayName = ""

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val name = parser.name
            when {
                name.contains("href") -> href = readText(parser)
                name.contains("propstat") -> {
                    // 进入 propstat -> prop
                    while(parser.next() != XmlPullParser.END_TAG) {
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        if (parser.name.contains("prop")) {
                            // 读取属性
                            while(parser.next() != XmlPullParser.END_TAG) {
                                if (parser.eventType != XmlPullParser.START_TAG) continue
                                val propName = parser.name
                                when {
                                    propName.contains("getcontentlength") -> contentLength = readText(parser).toLongOrNull() ?: 0
                                    propName.contains("getcontenttype") -> contentType = readText(parser)
                                    propName.contains("displayname") -> displayName = readText(parser)
                                    propName.contains("resourcetype") -> {
                                        // 检查是否是文件夹
                                        val typeContent = readInnerResourceType(parser)
                                        if (typeContent) isCollection = true
                                    }
                                    else -> skip(parser)
                                }
                            }
                        } else {
                            skip(parser)
                        }
                    }
                }
                else -> skip(parser)
            }
        }
        
        // 兜底：如果没有 displayName，尝试从 href 截取
        if (displayName.isEmpty()) {
            val rawName = href.trim('/').substringAfterLast('/')
            try {
                displayName = java.net.URLDecoder.decode(rawName, "UTF-8")
            } catch (e: Exception) {
                displayName = rawName
            }
        }

        return WebDavResource(href, isCollection, contentLength, contentType, displayName)
    }

    private fun readInnerResourceType(parser: XmlPullParser): Boolean {
        var isCollection = false
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name.contains("collection")) {
                isCollection = true
                skip(parser) // collection 是个空标签
            } else {
                skip(parser)
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
            }
        }
    }
}