package component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import app.lidory.duckcount.android.ui.constant.Padding
import util.getColorsForContact

@Composable
fun ProfilePictureCard(
    uuid: String,
    fullName: String,
    modifier: Modifier = Modifier,
    profilePicture: String? = null,
) {
    if (profilePicture == null) {

        val colors = getColorsForContact(uuid = uuid, isDarkTheme = isSystemInDarkTheme())

        var boxSize by remember { mutableStateOf(IntSize.Zero) }

        Card(
            modifier = modifier
                .padding(Padding.medium)
                .aspectRatio(1f),
            colors = CardDefaults.cardColors().copy(
                containerColor = colors.containerColor,
                contentColor = colors.contentColor,
            ),
            shape = CircleShape,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size -> boxSize = size },
                contentAlignment = Alignment.Center,
            ) {
                val textSize = with(LocalDensity.current) {
                    (boxSize.width * 0.5f).toSp()
                }

                Text(
                    fontSize = textSize,
                    text = fullName.take(1),
                )
            }
        }
    } else {
        // TODO: show profile picture
    }
}
