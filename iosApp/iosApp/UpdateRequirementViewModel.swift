import Foundation
import Shared

/// Whether the API has refused this build, which it does with a `426` to any request, so that
/// `ContentView` can replace the whole app with `UpdateRequiredScreen`.
///
/// Never goes back to false while the process lives: updating the app ends the process, and a new
/// one asks again at its first request.
@MainActor
class UpdateRequirementViewModel: ObservableObject {
    @Published var isRequired = false

    private var cancellable: Cancellable?

    init() {
        let flow = KoinHelper().getUpdateRequirement().isRequired

        cancellable = FlowCollectorKt.collect(
            flow,
            onEach: { [weak self] value in
                guard let self else {
                    return
                }
                guard let isRequired = value as? KotlinBoolean else {
                    return
                }

                self.isRequired = isRequired.boolValue
            }, onCompletion: { _ in })
    }

    deinit {
        cancellable?.cancel()
    }
}
