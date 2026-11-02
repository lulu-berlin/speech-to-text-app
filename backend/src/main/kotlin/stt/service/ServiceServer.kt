package stt.service

import io.ktor.server.engine.EmbeddedServer
import org.slf4j.LoggerFactory
import java.net.BindException

private val logger = LoggerFactory.getLogger("stt.service.ServiceServer")

/**
 * Starts the service's server - built by [server] for a port - on the [durable] port, and, when that bind fails
 * because another program holds the port, on a free one the OS picks, which `advertise` then makes durable (roadmap
 * item 66, items 6 and D5). Whatever check ran before, the bind decides, so another program taking the port after
 * that check cannot make the start fail. [wait] is passed to `start`: true blocks until the server stops.
 */
fun startOnDurablePort(
    durable: Int,
    wait: Boolean,
    server: (port: Int) -> EmbeddedServer<*, *>,
): EmbeddedServer<*, *> {
    val first = server(durable)
    try {
        return first.start(wait = wait)
    } catch (e: BindException) {
        logger.info("Port {} is taken by another program; serving on a free port", durable, e)
        // The failed server's application started before the bind; stop it so its resources are released.
        first.stop(0, 0)
    }
    return server(ServiceInstance.ANY_FREE_PORT).start(wait = wait)
}
