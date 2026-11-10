import SwiftUI

/// Logs in to an existing account with its username and password.
struct LoginView: View {
    @StateObject private var viewModel: LoginViewModel

    private let onLoggedIn: @MainActor () -> Void

    @FocusState private var focusedField: Field?

    private enum Field {
        case username
        case password
    }

    init(callBacks: any AuthenticationCallBacks, onLoggedIn: @escaping @MainActor () -> Void) {
        _viewModel = StateObject(wrappedValue: LoginViewModel(callBacks: callBacks))
        self.onLoggedIn = onLoggedIn
    }

    var body: some View {
        Form {
            Section {
                TextField(
                    String(localized: "Username", bundle: .module),
                    text: Binding(get: { viewModel.username }, set: viewModel.updateUsername)
                )
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.next)
                .focused($focusedField, equals: .username)
                .onSubmit { focusedField = .password }

                SecureField(
                    String(localized: "Password", bundle: .module),
                    text: Binding(get: { viewModel.password }, set: viewModel.updatePassword)
                )
                .textContentType(.password)
                .submitLabel(.go)
                .focused($focusedField, equals: .password)
                .onSubmit(logIn)
            } footer: {
                FormErrors(
                    viewModel.usernameError?.message(
                        length: viewModel.trimmedUsername.count,
                        maxLength: viewModel.maxUsernameLength
                    ),
                    viewModel.error?.message
                )
            }
        }
        .disabled(viewModel.isLoading)
        .navigationTitle(Text("Welcome back!", bundle: .module))
        // Nothing to go back to while logging in.
        .navigationBarBackButtonHidden(viewModel.isLoading)
        .bottomBar {
            ConfirmButton("Log In", isLoading: viewModel.isLoading, action: logIn)
                .disabled(!viewModel.canLogIn)
        }
        .onAppear { focusedField = .username }
    }

    private func logIn() {
        focusedField = nil
        viewModel.logIn(onLoggedIn: onLoggedIn)
    }
}

/// The errors a form section points out, under it, one per line.
struct FormErrors: View {
    private let messages: [String]

    init(_ messages: String?...) {
        self.messages = messages.compactMap { $0 }
    }

    var body: some View {
        if !messages.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(messages, id: \.self) { message in
                    Text(message)
                }
            }
            .foregroundStyle(.red)
        }
    }
}

#Preview {
    NavigationStack {
        LoginView(callBacks: PreviewAuthenticationCallBacks(), onLoggedIn: {})
    }
}
