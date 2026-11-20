import AppColors
import AppFonts
import SwiftUI

/// Where the user compares the safety number they share with a contact, read out in person or on a
/// call they trust, to tell that nobody stands between the two of them, and marks it verified, as
/// Android's `ContactVerificationScreen`.
struct ContactVerificationView: View {
    @StateObject private var viewModel: ContactVerificationViewModel

    init(contactId: String, callBacks: any ContactCallBacks) {
        _viewModel = StateObject(wrappedValue: ContactVerificationViewModel(contactId: contactId, callBacks: callBacks))
    }

    var body: some View {
        content
            .navigationTitle(Text("Verify safety number", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .bottomBar {
                if case .available(_, let isVerified) = viewModel.verification {
                    ConfirmButton(
                        isVerified ? Text("Clear verification", bundle: .module) : Text("Mark as verified", bundle: .module),
                        isLoading: viewModel.isUpdating,
                        action: viewModel.toggleVerified
                    )
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.verification {
        case nil:
            ProgressView()

        case .notContact:
            ContentUnavailableView {
                Label {
                    Text("This person is no longer one of your contacts", bundle: .module)
                } icon: {
                    Image(systemName: "person.2.slash")
                }
                .font(.app(.title2))
            }

        case .unavailable(let error):
            ContentUnavailableView {
                Label {
                    Text(error.message)
                } icon: {
                    Image(systemName: "exclamationmark.triangle")
                }
                .font(.app(.title2))
            }

        case .identityChanged:
            ScrollView {
                VStack(spacing: 20) {
                    name

                    Image(systemName: "exclamationmark.octagon.fill")
                        .font(.largeTitle)
                        .foregroundStyle(AppColors.error)

                    Text("Your safety number with this contact has changed", bundle: .module)
                        .font(.app(.headline))

                    Text(
                        "A contact who reinstalls DuckCount or changes phones keeps the same safety number, so someone may be trying to intercept what you share with this contact. As a precaution, DuckCount no longer sends them anything or accepts anything from them.",
                        bundle: .module
                    )
                }
                .multilineTextAlignment(.center)
                .padding()
            }

        case .available(let safetyNumber, let isVerified):
            ScrollView {
                VStack(spacing: 20) {
                    name

                    SafetyNumberGrid(safetyNumber: safetyNumber)

                    Text(
                        "To verify the end-to-end encryption between you and this contact, compare the numbers above with the ones on their phone, in person or on a call you trust.",
                        bundle: .module
                    )
                    .multilineTextAlignment(.center)
                    .foregroundStyle(AppColors.onSurfaceVariant)

                    if isVerified {
                        VerifiedLabel()
                    }
                }
                .padding()
            }
        }
    }

    private var name: some View {
        Text(viewModel.fullName ?? unresolvedUserName)
            .font(.app(.title2))
            .multilineTextAlignment(.center)
    }
}

/// The safety number the user reads out against the one on the contact's phone, its twelve groups
/// of five digits in lines of four, monospaced so that the columns line up for whoever follows
/// along, as fewer to a line when four do not fit, with a large text size: the groups being all
/// the same width, three, two or one to a line still divide twelve. A screen reader reads it as one,
/// group after group.
struct SafetyNumberGrid: View {
    let safetyNumber: String

    var body: some View {
        let groups = safetyNumber.split(separator: " ").map(String.init)

        ViewThatFits(in: .horizontal) {
            ForEach([4, 3, 2, 1], id: \.self) { perLine in
                lines(of: groups, perLine: perLine)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(AppColors.surfaceContainerHighest, in: .rect(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Safety number: \(groups.joined(separator: ", "))", bundle: .module))
    }

    private func lines(of groups: [String], perLine: Int) -> some View {
        Grid(horizontalSpacing: 20, verticalSpacing: 16) {
            ForEach(Array(stride(from: 0, to: groups.count, by: perLine)), id: \.self) { start in
                GridRow {
                    // By place: two groups can be the same digits.
                    ForEach(start..<min(start + perLine, groups.count), id: \.self) { index in
                        Text(verbatim: groups[index])
                            .font(.title3.monospaced())
                            .fixedSize()
                    }
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        ContactVerificationView(contactId: "alice", callBacks: PreviewContactCallBacks())
    }
}
