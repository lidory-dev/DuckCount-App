package app.lidory.duckcount.android.ui.composable

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.composable.button.ButtonBottomPadding
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.constant.FontSize
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * A whole screen for something that stops the user and that nothing on screen could fix: the logo
 * above a [title] and a [message], and one button.
 *
 * The text scrolls on its own, so that at a large font scale all of it can still be read with the
 * button in reach.
 */
@Composable
fun NoticeScreen(
    title: String,
    message: String,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
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
                        painter = painterResource(R.drawable.notice_screen_logo),
                        contentDescription = null,
                        modifier = Modifier.size(160.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = title,
                        modifier = Modifier.padding(top = Padding.large),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = FontSize.large,
                        lineHeight = FontSize.large * 1.2f,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message,
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
                onClick = onButtonClick,
            ) {
                Text(
                    text = buttonText,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(ButtonBottomPadding))
        }
    }
}
