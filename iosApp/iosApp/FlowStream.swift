import Foundation
import Shared

/// What `flow` emits, each value as `transform` makes it, until the stream is no longer followed,
/// which stops collecting it: how the screens' callbacks hand a Kotlin flow to a view model.
@MainActor
func stream<Element>(
    _ flow: any Kotlinx_coroutines_coreFlow,
    _ transform: @escaping (Any?) -> Element
) -> AsyncStream<Element> {
    AsyncStream { continuation in
        let collection = FlowCollectorKt.collect(
            flow,
            onEach: { value in
                continuation.yield(transform(value))
            },
            onCompletion: { _ in
                continuation.finish()
            }
        )

        continuation.onTermination = { _ in
            collection.cancel()
        }
    }
}
