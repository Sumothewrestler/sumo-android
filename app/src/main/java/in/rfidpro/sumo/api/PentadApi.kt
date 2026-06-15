package `in`.rfidpro.sumo.api

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Pentad backend API — every Sumo Android call lives here.
 *
 * Endpoints match the live pentadsumo backend:
 *   POST /api/auth/login/             returns AuthResp + sets refresh cookie
 *   POST /api/auth/refresh/           handled inside AuthInterceptor (not here)
 *   POST /api/agent/chat/             { message } -> { reply }
 *   POST /api/agent/speak/            { text, language_code } -> audio/wav bytes
 *   GET  /api/tasks/task/             auto-router CRUD with query filters
 */
interface PentadApi {

    @POST("/api/auth/login/")
    suspend fun login(@Body body: LoginReq): Response<LoginResp>

    @POST("/api/agent/chat/")
    suspend fun chat(@Body body: ChatReq): Response<ChatResp>

    /**
     * TTS — returns audio/wav binary. We pull the raw ResponseBody and
     * stream it into MediaPlayer (see AudioPlayback).
     */
    @POST("/api/agent/speak/")
    suspend fun speak(@Body body: SpeakReq): Response<ResponseBody>

    /**
     * Tasks list with query filters. The auto-router accepts:
     *   ?status__in=pending,in_progress
     *   ?assignedTo=<id>
     *   ?dueDate=2026-06-15
     *   ?ordering=dueDate
     */
    @GET("/api/tasks/task/")
    suspend fun tasks(
        @Query("status__in") statusIn: String = "pending,in_progress",
        @Query("dueDate") dueDate: String? = null,
        @Query("ordering") ordering: String = "dueDate",
    ): Response<TasksResp>
}


// ----- Request / response DTOs ----------------------------------------

@JsonClass(generateAdapter = true)
data class LoginReq(
    val username: String,
    val password: String,
)

@JsonClass(generateAdapter = true)
data class LoginResp(
    val accessToken: String,
    val user: UserDto? = null,
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Long? = null,
    val username: String? = null,
    val email: String? = null,
)

@JsonClass(generateAdapter = true)
data class ChatReq(
    val message: String,
    val reset: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ChatResp(
    val reply: String? = null,
    val error: String? = null,
)

@JsonClass(generateAdapter = true)
data class SpeakReq(
    val text: String,
    val language_code: String,
    val translate_from: String? = null,
)

/**
 * DRF auto-router list response. The standard shape is paginated;
 * we ask for ordering=dueDate and rely on the small task volume
 * (single user) so the default page size returns everything.
 */
@JsonClass(generateAdapter = true)
data class TasksResp(
    val count: Int = 0,
    val results: List<TaskDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TaskDto(
    val id: Long,
    val title: String,
    val status: String,
    val priority: String,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val assignedTo: Long? = null,
    val createdBy: Long? = null,
)
