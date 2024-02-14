package com.manetevaluation.pong.sync

import java.io.*
import java.util.Date
import java.util.concurrent.Semaphore

class LocalReport {
    companion object {
        private var semaphoreContactsReport : Semaphore = Semaphore(1)
        private var semaphoreMessageGenerationReport : Semaphore = Semaphore(1)
        private var semaphoreSafeContactReport : Semaphore = Semaphore(1)
        private var semaphoreNewDisconnection : Semaphore = Semaphore(1)
        private var semaphoreNewSafeDisconnection : Semaphore = Semaphore(1)
        private var semaphoreNewReceivedMessage : Semaphore = Semaphore(1)
        private var semaphoreNewSentMessage : Semaphore = Semaphore(1)
        private var semaphoreNewDeliveredMessage : Semaphore = Semaphore(1)
        private var semaphoreNewDetailedConnection : Semaphore = Semaphore(1)

        // Check only contacts stablished with other nodes. Maybe they do not handshake.
        fun addNewContact(absolutePath : File, connection : Connection){
            this.semaphoreContactsReport.acquire()
            val file: File = File(absolutePath, "NewContactsReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,ContactedNode")
            }
            writer.write("${connection.timestamp},${connection.sourceNode},${connection.destinationNode}\n")
            writer.close()
            this.semaphoreContactsReport.release()
        }

        // Check safe contacts stablished with other nodes. Handshake is successful.
        fun addNewSafeContact(absolutePath : File, connection : Connection){
            this.semaphoreSafeContactReport.acquire()
            val file: File = File(absolutePath, "NewSafeContactsReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,ContactedNode")
            }
            writer.write("${connection.timestamp},${connection.sourceNode},${connection.destinationNode}\n")
            writer.close()
            this.semaphoreSafeContactReport.release()
        }

        // Check disconnections in contacts.
        fun addNewDisconnection(absolutePath: File, connection: Connection){
            this.semaphoreNewDisconnection.acquire()
            val file: File = File(absolutePath, "NewDisconnectionReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,ContactedNode,ContactLength")
            }
            writer.write("${connection.timestamp},${connection.sourceNode},${connection.destinationNode},${connection.encounterlength}\n")
            writer.close()
            this.semaphoreNewDisconnection.release()
        }

        // Check disconnections in contacts after handshake.
        fun addNewSafeDisconnection(absolutePath: File, connection : Connection){
            this.semaphoreNewSafeDisconnection.acquire()
            val file: File = File(absolutePath, "NewSafeDisconnectionReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,ContactedNode")
            }
            writer.write("${connection.timestamp},${connection.sourceNode},${connection.destinationNode}\n")
            writer.close()
            this.semaphoreNewSafeDisconnection.release()
        }

        fun addNewReceivedMessage(absolutePath: File, message : Message){
            this.semaphoreNewReceivedMessage.acquire()
            val file: File = File(absolutePath, "NewReceivedMessageReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,DestinationNode,PreviousHop,IdMessage,HopNumber,GeneratedDate")
            }
            writer.write("${message.timestamp},${message.sourceNode},${message.destinationNode},${message.previousHop},${message.messageId},${message.hopNumber},${message.generatedDate}\n")
            writer.close()
            this.semaphoreNewReceivedMessage.release()
        }

        fun addNewForwardedMessage(absolutePath: File, message : Message){
            this.semaphoreNewSentMessage.acquire()
            val file: File = File(absolutePath, "NewSentMessageReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,DestinationNode,PreviousHop,IdMessage,GeneratedDate")
            }
            writer.write("${message.timestamp},${message.sourceNode},${message.destinationNode}, ${message.previousHop}, ${message.messageId}, ${message.generatedDate}\n")
            writer.close()
            this.semaphoreNewSentMessage.release()
        }

        fun addNewDeliveredMessage(absolutePath: File, message: Message, latency:Long){
            this.semaphoreNewDeliveredMessage.acquire()
            val file: File = File(absolutePath, "NewDeliveredMessageReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,DestinationNode,ConnectedNode,IdMessage,HopNumber,Latency,GeneratedDate")
            }
            writer.write("${message.timestamp},${message.sourceNode},${message.destinationNode},${message.previousHop},${message.messageId},${message.hopNumber},${latency},${message.generatedDate}\n")
            writer.close()
            this.semaphoreNewDeliveredMessage.release()
        }

        fun addNewGeneratedMessage(absolutePath : File, message : Message){
            this.semaphoreMessageGenerationReport.acquire()
            val file: File = File(absolutePath, "NewMessagesReport.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,SourceNode,DestinationNode,MessageID")
            }
            writer.write("${message.timestamp},${message.sourceNode},${message.destinationNode},${message.messageId}\n")
            writer.close()
            this.semaphoreMessageGenerationReport.release()
        }

        fun addNewDetailedConnection(absolutePath: File, detailedConnection: DetailedConnection){
            this.semaphoreNewDetailedConnection.acquire()
            val file: File = File(absolutePath, "NewDetailedConnection.csv")
            var writer: PrintWriter? = null
            writer = PrintWriter(BufferedWriter(FileWriter(file, true)))
            // Escribir la línea de encabezado si el archivo no existe o está vacío
            if (file.length() == 0L) {
                writer.println("Timestamp,sourceNode,destinationNode,rssi,throughput,BER,latency,distance,disconnections,success")
            }
            writer.write("${detailedConnection.timestamp},${detailedConnection.sourceNode},${detailedConnection.destinationNode}," +
                    "${detailedConnection.rssi},${detailedConnection.throughput},${detailedConnection.BER},${detailedConnection.latency}," +
                    "${detailedConnection.distance},${detailedConnection.disconnections},${detailedConnection.success}\n")
            writer.close()
            this.semaphoreNewDetailedConnection.release()
        }
    }
}

data class Connection(
    val timestamp : Date,
    val sourceNode : String,
    val destinationNode : String,
    val encounterlength : Long?
)

data class Message(
    val timestamp : String,
    val sourceNode : String,
    val destinationNode : String,
    val previousHop : String,
    val messageId : Long,
    val hopNumber : Int,
    val generatedDate : Date?
)

data class DetailedConnection(
    val timestamp: Date,
    val sourceNode: String,
    val destinationNode: String,
    val rssi: Int?,
    val throughput: Long,
    val BER: Long,
    val latency: Long,
    val distance: Double,
    var disconnections: Int,
    var success : Boolean
)