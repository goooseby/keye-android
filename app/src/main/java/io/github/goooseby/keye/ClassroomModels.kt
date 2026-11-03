package io.github.goooseby.keye

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Query authentication may rotate while the underlying page stays the same. */
internal fun classroomPageKey(value: String): String {
    val uri=URI(value)
    require(uri.scheme=="https" && uri.host!=null) {"课件页面地址无效"}
    val transient=setOf("token","signature","expires","ossaccesskeyid","awsaccesskeyid","auth_key","x-oss-signature","x-oss-expires","x-oss-credential","x-oss-date","x-oss-signature-version","x-oss-security-token")
    val query=uri.rawQuery.orEmpty().split('&').filter {part ->
        val name=URLDecoder.decode(part.substringBefore('='),"UTF-8").lowercase()
        part.isNotBlank() && name !in transient && !name.startsWith("x-amz-")
    }.sorted().joinToString("&")
    val identity="${uri.host.lowercase()}:${uri.port}${uri.rawPath}?$query"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
}

internal fun classroomRetryMillis(failures: Int,intervalSeconds: Int): Long =
    (intervalSeconds.coerceIn(3,10)*1000L*(1L shl (failures-1).coerceIn(0,4))).coerceAtMost(30_000L)

internal fun pendingClassroomPages(known: Set<String>,incoming: List<ClassroomPage>): List<ClassroomPage> =
    incoming.distinctBy {it.key}.filterNot {it.key in known}

internal fun classroomUnseenPages(total: Int,seen: Int)= (total-seen).coerceAtLeast(0)

data class ClassroomPage(val key: String,val url: String)
enum class ClassroomStage { Connecting, Waiting, Connected, Paused, Retrying, NeedsLogin, Ended }
data class ClassroomSession(
    val entry: ScheduleEntry,val materialId: String?=null,val stage: ClassroomStage=ClassroomStage.Connecting,
    val intervalSeconds: Int=3,val fetching: Boolean=false,val remoteCount: Int=0,
    val checkedAt: Long=0,val message: String="正在连接课堂",val retrySeconds: Int=0
)

/** A source is injected for deterministic development checks; production uses SchoolApi. */
interface ClassroomPageSource {
    val credentials: SchoolAuth? get()=null
    fun owns(entry: ScheduleEntry): Boolean
    fun pages(entry: ScheduleEntry): List<ClassroomPage>
    fun image(page: ClassroomPage): ByteArray
}

internal fun classroomAppendTarget(following: Boolean,current: Int,count: Int): Int =
    if(count<=0) 0 else if(following) count-1 else current.coerceIn(0,count-1)

/** Insert a backfilled page in catalogue order, preserving page-bound user data. */
internal fun insertClassroomPage(material: StudyMaterial,page: PageFile,catalog: List<ClassroomPage>): StudyMaterial {
    val order=catalog.map {it.key}
    val rank=order.indexOf(page.remoteKey)
    val insertion=material.pages.indexOfFirst {existing ->
        val other=order.indexOf(existing.remoteKey)
        rank>=0 && other>rank
    }.let {if(it<0) material.pages.size else it}
    fun shifted(number: Int)=if(number>insertion) number+1 else number
    val pages=material.pages.toMutableList().apply {add(insertion,page)}
    return material.copy(pages=pages,excluded=material.excluded.map(::shifted).toSet(),
        bookmarks=material.bookmarks.map(::shifted).toSet(),
        pageNotes=material.pageNotes.mapKeys {shifted(it.key)},
        lastPage=if(material.pages.isEmpty()) 1 else shifted(material.lastPage),
        revision=material.revision+1,touched=System.currentTimeMillis())
}
