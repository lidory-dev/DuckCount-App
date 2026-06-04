import SwiftUI

public struct BeneficiariesPickerView<ViewModel: CreateExpenseFormState>: View {
    @ObservedObject private var viewModel: ViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedUuids: Set<String> = []

    public init(viewModel: ViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        List(viewModel.users) { user in
            Button {
                toggle(user.id)
            } label: {
                HStack {
                    Image(systemName: selectedUuids.contains(user.id) ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(selectedUuids.contains(user.id) ? Color.accentColor : .secondary)
                        .font(.title3)
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
                }
            }
        }
        .navigationTitle("Beneficiaries")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $viewModel.searchQuery, prompt: "Search contacts")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    toggleSelectAll()
                } label: {
                    Image(systemName: allSelected ? "checklist.checked" : "checklist.unchecked")
                }
                .disabled(viewModel.users.isEmpty)
                .accessibilityLabel(allSelected ? "Deselect All" : "Select All")
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") {
                    viewModel.updateBeneficiaries(selectedUserUuids: selectedUuids)
                    dismiss()
                }
            }
        }
        .onAppear {
            selectedUuids = Set(viewModel.beneficiaries.map(\.userUuid))
        }
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

    private var allSelected: Bool {
        !viewModel.users.isEmpty
            && viewModel.users.allSatisfy {
                selectedUuids.contains($0.id)
            }
    }

    private func toggle(_ id: String) {
        if selectedUuids.contains(id) {
            selectedUuids.remove(id)
        } else {
            selectedUuids.insert(id)
        }
    }

    private func toggleSelectAll() {
        if allSelected {
            for user in viewModel.users {
                selectedUuids.remove(user.id)
            }
        } else {
            for user in viewModel.users {
                selectedUuids.insert(user.id)
            }
        }
    }
}
