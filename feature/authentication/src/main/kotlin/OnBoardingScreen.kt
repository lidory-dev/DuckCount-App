import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.composable.button.DuckCountOutlinedButton
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.authentication.R

@Composable
fun OnBoardingScreen(
    @StringRes appName: Int,
    @StringRes onBoardingTitle: Int,
    @StringRes onBoardingDescription: Int,
    navigateToLoginScreen: () -> Unit,
    navigateToRegisterScreen: () -> Unit,
) {
    val focusManager: FocusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    Scaffold(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                focusManager.clearFocus()
            }
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
                    text = stringResource(appName),
                    fontWeight = FontWeight.Bold,
                    fontSize = FontSize.title,
                    lineHeight = FontSize.title,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(onBoardingTitle),
                    modifier = Modifier.padding(top = Padding.large),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = FontSize.large,
                    lineHeight = FontSize.large * 1.2f,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(onBoardingDescription),
                    modifier = Modifier.padding(top = Padding.small),
                    fontSize = FontSize.medium,
                    lineHeight = FontSize.medium * 1.4f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DuckCountButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = navigateToRegisterScreen,
                ) {
                    Text(
                        text = stringResource(R.string.register),
                        fontWeight = FontWeight.Bold,
                    )
                }

                DuckCountOutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = navigateToLoginScreen,
                ) {
                    Text(
                        text = stringResource(R.string.login),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}
