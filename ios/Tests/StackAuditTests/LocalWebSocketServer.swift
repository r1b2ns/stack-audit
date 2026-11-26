import Foundation
import Network

final class LocalWebSocketServer {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "stackaudit.tests.ws")
    private var connections: [NWConnection] = []

    init() throws {
        let options = NWProtocolWebSocket.Options()
        options.autoReplyPing = true
        options.setClientRequestHandler(DispatchQueue(label: "stackaudit.tests.ws.handshake")) { protocols, _ in
            NWProtocolWebSocket.Response(status: .accept, subprotocol: protocols.first)
        }
        let parameters = NWParameters.tcp
        parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
        listener = try NWListener(using: parameters, on: .any)
    }

    func start() async throws -> URL {
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            var resumed = false
            listener.stateUpdateHandler = { [listener] state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    continuation.resume(returning: listener.port?.rawValue ?? 0)
                case let .failed(error):
                    resumed = true
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.start(queue: queue)
        }
        return URL(string: "ws://127.0.0.1:\(port)/chat")!
    }

    func stop() {
        listener.cancel()
        queue.sync { connections.forEach { $0.cancel() } }
    }

    private func accept(_ connection: NWConnection) {
        connections.append(connection)
        connection.start(queue: queue)
        echo(connection)
    }

    private func echo(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] content, context, _, error in
            guard error == nil, let context,
                  let metadata = context.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            else { return }
            switch metadata.opcode {
            case .text, .binary:
                let reply = NWProtocolWebSocket.Metadata(opcode: metadata.opcode)
                let replyContext = NWConnection.ContentContext(identifier: "echo", metadata: [reply])
                connection.send(content: content, contentContext: replyContext, isComplete: true, completion: .idempotent)
            case .close:
                connection.cancel()
                return
            default:
                break
            }
            self?.echo(connection)
        }
    }
}
