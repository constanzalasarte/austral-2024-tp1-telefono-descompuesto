package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.*
import ar.edu.austral.inf.sd.server.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForEntity
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class ApiServicesImpl @Autowired constructor(
    private val restTemplate: RestTemplate
) : RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    // Coordinator's list of participants
    private val nodes: MutableList<Node> = mutableListOf()

    // Configuration
    @Value("\${server.name:nada}")
    private val myServerName: String = ""
    @Value("\${server.host:localhost}")
    private val myServerHost: String = "localhost"
    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    @Value("\${server.timeout:300000}")
    private val timeout: Int = 300000
    @Value("\${register.host:}")
    private val registerHost: String = ""
    @Value("\${register.port:8080}")
    private val registerPort: Int = -1
    @Value("\${maxTimeouts:10}")
    private val maxTimeouts: Int = 10

    private var timeouts = 0

    // Participant's data
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val mySalt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val myUUID : UUID = newUUID()
    private var timestamp = 0
    private var nextNodeAfterTimestamp: RegisterResponse? = null

    // Current game data
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): ResponseEntity<RegisterResponse> {
        try {
            Base64.getDecoder().decode(salt)
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Could not decode salt as Base64")
        }

        val existingNode = nodes.find { it.uuid == uuid }
        if (existingNode != null) {
            if (existingNode.salt == salt) {
                val nextNodeIndex = nodes.indexOf(existingNode) - 1
                val nextNode = nodes[nextNodeIndex]
                return ResponseEntity(RegisterResponse(nextNode.host, nextNode.port, timeout, xGameTimestamp), HttpStatus.ACCEPTED)
            } else {
                throw UnauthorizedException("Invalid salt")
            }
        }

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, timeout, xGameTimestamp)
            val meNode = Node(myServerHost, myServerPort, myServerName, myUUID, mySalt)
            nodes.add(meNode)
            me
        } else {
            val lastNode = nodes.last()
            RegisterResponse(lastNode.host, lastNode.port, timeout, xGameTimestamp)
        }
        val node = Node(host!!, port!!, name!!, uuid!!, salt!!)
        nodes.add(node)

        return ResponseEntity(RegisterResponse(nextNode.nextHost, nextNode.nextPort, timeout, xGameTimestamp), HttpStatus.OK)
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), mySalt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            val newSignatures = signatures.items + clientSign(message, receivedContentType)
            sendRelayMessage(message, receivedContentType, nextNode!!, Signatures(newSignatures), xGameTimestamp!!)
        } else {
            // me llego algo, no lo tengo que pasar
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            this.xGameTimestamp += 1
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }

    override fun sendMessage(body: String): PlayResponse {
        if (timeouts >= maxTimeouts) throw BadRequestException("Game is closed")

        if (nodes.isEmpty()) {
            val me = Node(myServerHost, myServerPort, myServerName, myUUID, mySalt)
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        val expectedSignatures = getExpectedSignatures(body, contentType)
        sendRelayMessage(body, contentType, registerResponse(nodes.last()), Signatures(listOf()), xGameTimestamp)
        resultReady.await(timeout.toLong(), TimeUnit.MILLISECONDS)
        resultReady = CountDownLatch(1)
        checkCurrentMessage(expectedSignatures)
        return currentMessageResponse.value!!
    }

    private fun checkCurrentMessage(expectedSignatures: Signatures) {
        if (currentMessageWaiting.value != null) {
            timeouts += 1
            throw GatewayTimeoutException("Last relay was not received on time")
        }
        if (!compareSignatures(expectedSignatures, currentMessageResponse.value!!.signatures)) {
            throw InternalServerErrorException("Missing signatures")
        }
        if (currentMessageWaiting.value!!.originalHash != currentMessageWaiting.value!!.receivedHash) {
            throw ServiceUnavailableException("Received different hash than original")
        }
    }

    private fun compareSignatures(expected: Signatures, actual: Signatures): Boolean {
        val expectedSignatures = expected.items
        val realSignatures = actual.items.reversed()

        if (expectedSignatures.size != realSignatures.size) return false

        for (i in expectedSignatures.indices) {
            if (expectedSignatures[i].hash != realSignatures[i].hash) return false
        }
        return true
    }

    private fun registerResponse(node: Node): RegisterResponse =
        RegisterResponse(
            node.host,
            node.port,
            timeout,
            -1
        )

    private fun getExpectedSignatures(body: String, contentType: String?): Signatures {
        val signatures = mutableListOf<Signature>()
        for (i in 1..< nodes.size){
            val node = nodes[i]
            val hash = doHash(body.encodeToByteArray(), node.salt)
            signatures.add(Signature(node.name, hash, contentType, body.length))
        }

        return Signatures(signatures)
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        val node = nodes.find { it.uuid == uuid!! }
        if (node == null)
            throw NotFoundException("Node with uuid: $uuid not found")
        else if(node.salt != salt)
            throw BadRequestException("Invalid data")
        val index = nodes.indexOf(node)
        reconfigureNodes(index)
        nodes.removeAt(index)
        return "Unregister Successful"
    }

    private fun reconfigureNodes(index: Int) {
        if (index < nodes.size - 1) {
            val previousNode = nodes[index + 1]
            val nextNode = nodes[index - 1]
            val url = getUnregisterURL(previousNode, nextNode)
            val request = getUnregisterRequest()
            try {
                restTemplate.postForEntity<String>(url, request)
            } catch (e: RestClientException) {
                print("Could not reconfigure to: $url")
                throw e
            }
        }
    }

    private fun getUnregisterRequest(): HttpEntity<Map<String, MutableList<String>>> {
        val requestHeaders = HttpHeaders().apply {
            add("X-Game-Timestamp", xGameTimestamp.toString())
        }
        val request = HttpEntity(requestHeaders.toMap())
        return request
    }

    private fun getUnregisterURL(
        previousNode: Node,
        nextNode: Node
    ): String {
        val reconfigureUrl = "http://${previousNode.host}:${previousNode.port}/reconfigure"
        val reconfigureParams =
            "?uuid=${previousNode.uuid}&salt=${previousNode.salt}&nextHost=${nextNode.host}&nextPort=${nextNode.port}"

        val url = reconfigureUrl + reconfigureParams
        return url
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        if(uuid != myUUID || salt != mySalt) {
            throw BadRequestException("Invalid data")
        }
        nextNodeAfterTimestamp = RegisterResponse(nextHost!!, nextPort!!, timeout, xGameTimestamp!!)
        return "Reconfigured node $myUUID"
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        val registerUrl = "http://$registerHost:$registerPort/register-node"
        val registerParams = "?host=localhost&port=$myServerPort&name=$myServerName&uuid=$myUUID&salt=$mySalt&name=$myServerName"
        val url = registerUrl + registerParams

        try {
            val response = restTemplate.postForEntity<RegisterResponse>(url)

            val registerNodeResponse: RegisterResponse = response.body!!
            println("nextNode = $registerNodeResponse")
            timestamp = registerNodeResponse.xGameTimestamp
            nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, timeout, registerNodeResponse.xGameTimestamp) }
        } catch (e: RestClientException) {
            print("Could not register to: $registerUrl")
        }
    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures,
        timestamp: Int
    ) {
        checkTimestamp(timestamp)
        checkIfNextNodeIsAvailableForChange(timestamp)

        val url = "http://${relayNode.nextHost}:${relayNode.nextPort}/relay"
        val request = getRequest(timestamp, body, contentType, signatures)
        try {
            restTemplate.postForEntity<Map<String, Any>>(url, request)
        } catch (e: RestClientException) {
            sendFailedPlayToHost(request, url)
        }

        this.timestamp = timestamp
    }

    private fun sendFailedPlayToHost(
        request: HttpEntity<LinkedMultiValueMap<String, Any>>,
        url: String
    ): Nothing {
        val hostUrl = "http://${registerHost}:${registerPort}/relay"
        restTemplate.postForEntity<Map<String, Any>>(hostUrl, request)

        throw ServiceUnavailableException("Could not relay message to: $url")
    }

    private fun getRequest(
        timestamp: Int,
        body: String,
        contentType: String,
        signatures: Signatures,
    ): HttpEntity<LinkedMultiValueMap<String, Any>> {
        val bodyParts = getBody(contentType, body, signatures)
        val requestHeaders = HttpHeaders().apply {
            setContentType(MediaType.MULTIPART_FORM_DATA)
            add("X-Game-Timestamp", timestamp.toString())
        }
        val request = HttpEntity(bodyParts, requestHeaders)
        return request
    }

    private fun getBody(
        contentType: String,
        body: String,
        signatures: Signatures
    ): LinkedMultiValueMap<String, Any> {
        val messageHeaders = HttpHeaders().apply { setContentType(MediaType.parseMediaType(contentType)) }
        val messagePart = HttpEntity(body, messageHeaders)

        val signatureHeaders = HttpHeaders().apply { setContentType(MediaType.APPLICATION_JSON) }
        val signaturesPart = HttpEntity(signatures, signatureHeaders)

        val bodyParts = LinkedMultiValueMap<String, Any>().apply {
            add("message", messagePart)
            add("signatures", signaturesPart)
        }
        return bodyParts
    }

    private fun checkTimestamp(timestamp: Int) {
        if (this.timestamp < timestamp) {
            throw BadRequestException("Invalid timestamp")
        }
    }

    private fun checkIfNextNodeIsAvailableForChange(timestamp: Int) {
        if (nextNodeAfterTimestamp != null && timestamp >= nextNodeAfterTimestamp!!.xGameTimestamp) {
            this.timestamp = nextNodeAfterTimestamp!!.xGameTimestamp
            nextNode = nextNodeAfterTimestamp
            nextNodeAfterTimestamp = null
        }
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), mySalt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), mySalt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newUUID(): UUID = UUID.randomUUID()
    }
}