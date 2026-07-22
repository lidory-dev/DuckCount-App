package app.lidory.duckcount.settings.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.lidory.duckcount.android.ui.composable.button.DuckCountButton
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.R

@Composable
fun PromotionalCard(
    modifier: Modifier = Modifier,
    learnMoreButtonUrl: String,
) {
    val uriHandler = LocalUriHandler.current

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Padding.medium,
                    end = Padding.medium,
                    top = Padding.large,
                    bottom = Padding.medium,
                ),
            text = stringResource(R.string.promotional_card_title),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )

        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Padding.medium),
            text = stringResource(R.string.promotional_card_description),
            textAlign = TextAlign.Center,
        )

        DuckCountButton(
            modifier = Modifier
                .padding(
                    vertical = Padding.large,
                    horizontal = Padding.medium,
                )
                .align(Alignment.CenterHorizontally),
            onClick = {
                uriHandler.openUri(learnMoreButtonUrl)
            },
        ) {
            Text(stringResource(R.string.learn_more))
        }
    }
}
