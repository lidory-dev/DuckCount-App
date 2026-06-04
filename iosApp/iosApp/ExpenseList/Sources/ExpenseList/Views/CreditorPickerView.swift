import SwiftUI

public struct CreditorPickerView<ViewModel: CreateExpenseFormState>: View {
    @ObservedObject private var viewModel: ViewModel
    @Environment(\.dismiss) private var dismiss

    public init(viewModel: ViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        List(viewModel.users) { user in
            Button {
                viewModel.selectCreditor(user)
                dismiss()
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(user.fullName)
                            .foregroundStyle(.primary)
                        if !user.username.isEmpty {
                            Text("@\(user.username)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if viewModel.creditor?.id == user.id {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                    }
                }
            }
        }
        .navigationTitle("Paid by")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $viewModel.searchQuery, prompt: "Search contacts")
        .onDisappear {
            viewModel.clearSearchQuery()
        }
        .overlay {
            if viewModel.users.isEmpty {
                ContentUnavailableView(
                    "No contacts",
                    systemImage: "person.crop.circle.badge.questionmark"
                )
            }
        }
    }
}
