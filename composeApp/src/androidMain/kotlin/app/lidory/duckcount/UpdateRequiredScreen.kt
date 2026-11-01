package app.lidory.duckcount

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.button.ButtonBottomPadding
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * What the whole app is once the server has refused this build, see [AppViewModel.isUpdateRequired],
 * with no way past it: the server answers nothing else this build asks.
 *
 * The text scrolls on its own, so that at a large font scale all of it can still be read with the
 * button in reach.
 */
@Composable
fun UpdateRequiredScreen() {
    val uriHandler = LocalUriHandler.current
    val packageName = LocalContext.current.packageName

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Padding.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.update_required_logo),
                        contentDescription = null,
                        modifier = Modifier.size(160.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.update_required_title),
                        modifier = Modifier.padding(top = Padding.large),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = FontSize.large,
                        lineHeight = FontSize.large * 1.2f,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.update_required_message),
                        modifier = Modifier.padding(top = Padding.small),
                        fontSize = FontSize.medium,
                        lineHeight = FontSize.medium * 1.4f,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            DuckCountButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // A phone with no store to take the first has a browser to take the second.
                    // The handler reports an activity not found as an IllegalArgumentException.
                    try {
                        uriHandler.openUri("market://details?id=$packageName")
                    } catch (_: IllegalArgumentException) {
                        uriHandler.openUri("https://play.google.com/store/apps/details?id=$packageName")
                    }
                },
            ) {
                Text(
                    text = stringResource(R.string.update_required_button),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(ButtonBottomPadding))
        }
    }
}
