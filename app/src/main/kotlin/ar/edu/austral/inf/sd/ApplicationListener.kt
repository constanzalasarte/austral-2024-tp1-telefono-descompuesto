package ar.edu.austral.inf.sd

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ApplicationListener {
    @Autowired
    lateinit var apiServices: ApiServicesImpl

    @Value("\${register.host:}")
    var registerHost: String = ""
    @Value("\${register.port:-1}")
    var registerPort: Int = -1

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(event: ApplicationReadyEvent) {
        println("register url: $registerHost/$registerPort")
        if (registerHost != "" && registerPort != -1) {
            println("me voy a registrar en el server $registerHost:$registerPort")
            apiServices.registerToServer(registerHost, registerPort)
        }
    }


}