import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.authentication.R

@Composable
fun PasswordLeakScreen(
    leakAmount: Int,
    navigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(innerPadding)
                .padding(horizontal = Padding.extraLarge),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Column {
                Text(
                    text = stringResource(R.string.watch_out),
                    fontWeight = FontWeight.Bold,
                    fontSize = FontSize.large,
                    lineHeight = FontSize.title,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // TODO: Omit the plural if the password has only been leaked once.
                Text(
                    text = stringResource(R.string.password_leak_times, leakAmount),
                    modifier = Modifier.padding(top = Padding.large),
                    fontWeight = FontWeight.Normal,
                    fontSize = FontSize.medium,
                    lineHeight = FontSize.medium * 1.2f,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.choose_new_password),
                    modifier = Modifier.padding(top = Padding.large),
                    fontWeight = FontWeight.Normal,
                    fontSize = FontSize.medium,
                    lineHeight = FontSize.medium * 1.2f,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.change_password_everywhere),
                    modifier = Modifier.padding(top = Padding.large),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = FontSize.medium,
                    lineHeight = FontSize.medium * 1.2f,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = navigateBack,
            ) {
                Text(
                    text = stringResource(R.string.noted),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}
