import AppFonts
import SwiftUI

/// The contact requests waiting for the user, each to accept, decline, or decline and block its
/// sender, as Android's `ContactRequestListScreen`.
public struct ContactRequestListView: View {
    @StateObject private var viewModel: ContactRequestListViewModel

    /// Whether the user pulled the list to refresh it, which shows its own spinner.
    @State private var isPulling = false

    public init(callBacks: any ContactCallBacks) {
        _viewModel = StateObject(wrappedValue: ContactRequestListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        Group {
            if let requests = viewModel.requests {
                // A list even with no request in it, the only thing that can be pulled to refresh.
                List(requests) { request in
                    ContactRequestRow(
                        request: request,
                        answer: viewModel.answers[request.id],
                        onAccept: { viewModel.accept(request) },
                        onRefuse: { viewModel.refuse(request) },
                        onBlock: { viewModel.block(request) }
                    )
                }
                .overlay {
                    if requests.isEmpty {
                        // A pull shows a spinner of its own.
                        if viewModel.isLoading && !isPulling {
                            ProgressView()
                        } else {
                            ContentUnavailableView {
                                Label {
                                    Text("No contact requests", bundle: .module)
                                } icon: {
                                    Image(systemName: "envelope.open")
                                }
                                .font(.app(.title2))
                            }
                            // For the pull to reach the list under it.
                            .allowsHitTesting(false)
                        }
                    }
                }
                .refreshable {
                    isPulling = true
                    await viewModel.refresh()
                    isPulling = false
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(Text("Contact requests", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        // A request only shows once a refresh opened it, so the screen asks for one as it opens.
        .task {
            await viewModel.refresh()
        }
        .alert(
            viewModel.error?.message ?? "",
            isPresented: Binding(get: { viewModel.error != nil }, set: { if !$0 { viewModel.error = nil } })
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
    }
}

/// One contact request: who sent it, and the three ways to answer it. Every button stays disabled
/// while an answer is being sent, a request being answered once. Accept also waits until the
/// sender's name could be read: without it the row says nothing of who would be handed the user's
/// profile. Blocking does not wait for it: nothing is handed over. It asks first, `onBlock` only
/// coming once the user confirmed.
private struct ContactRequestRow: View {
    let request: ContactRequest
    let answer: ContactRequestListViewModel.Answer?
    let onAccept: () -> Void
    let onRefuse: () -> Void
    let onBlock: () -> Void

    @State private var isConfirmingBlock = false

    var body: some View {
        let name = request.senderFullName ?? unresolvedUserName

        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ContactAvatar(userUuid: request.senderUserUuid, name: name)

                Text(name)
                    .lineLimit(1)
            }

            HStack(spacing: 8) {
                answerButton(.block, role: .destructive, action: { isConfirmingBlock = true }) {
                    Text("Block", bundle: .module)
                }
                // On the button, for the dialog to point at it where it shows as a popover.
                .confirmationDialog(
                    Text("Block this user?", bundle: .module),
                    isPresented: $isConfirmingBlock,
                    titleVisibility: .visible
                ) {
                    Button(role: .destructive, action: onBlock) {
                        Text("Block", bundle: .module)
                    }
                } message: {
                    Text(
                        "Their request will be declined and they won’t be able to send you contact requests any more. They won’t be told.",
                        bundle: .module
                    )
                }

                answerButton(.refuse, action: onRefuse) {
                    Text("Decline", bundle: .module)
                }

                answerButton(.accept, isProminent: true, action: onAccept) {
                    Text("Accept", bundle: .module)
                }
                .disabled(request.senderFullName == nil)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func answerButton(
        _ kind: ContactRequestListViewModel.Answer,
        role: ButtonRole? = nil,
        isProminent: Bool = false,
        action: @escaping () -> Void,
        @ViewBuilder label: () -> Text
    ) -> some View {
        let button = Button(role: role, action: action) {
            label()
                .lineLimit(1)
                // Kept in place while hidden, for the button to keep its size.
                .opacity(answer == kind ? 0 : 1)
                .overlay {
                    if answer == kind {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity)
        }
        .disabled(answer != nil)

        // Bordered styles make each button a tap target of its own in a list's row.
        if isProminent {
            button.buttonStyle(.borderedProminent)
        } else {
            button.buttonStyle(.bordered)
        }
    }
}

#Preview {
    NavigationStack {
        ContactRequestListView(callBacks: PreviewContactCallBacks())
    }
}
