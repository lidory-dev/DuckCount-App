package app.lidory.duckcount.android.ui.composable.skeleton

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * What a screen shows in place of a spinner while it reads what it is about to show: the shape of
 * that content, in [Bone]s that pulse together.
 *
 * It fades in rather than appearing at once, so that a read done in a frame or two, which most
 * database reads are, shows next to nothing of it before the content replaces it. It is read out
 * as "Loading" and nothing else, and never scrolls: it lays its content out from the top, however
 * tall, and clips what goes past the room it has, so a screen can give it more rows than any
 * phone shows. Nothing in it may take a `weight` in its height, which it does not bound.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val description = stringResource(R.string.loading)

    val appearance = remember { Animatable(0f) }
    LaunchedEffect(appearance) {
        appearance.animateTo(1f, tween(durationMillis = APPEARANCE_MILLIS, easing = FastOutSlowInEasing))
    }

    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_LOWEST_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton pulse",
    )

    Column(
        modifier = modifier
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = description }
            // Read as the layer is drawn, so that the pulse redraws the skeleton without
            // recomposing or laying out any of it.
            .graphicsLayer { alpha = appearance.value * pulse.value }
            .wrapContentHeight(align = Alignment.Top, unbounded = true),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * Where something will be: a rounded block in the colour of the text it stands for, faint enough
 * to read as empty on the background and on a card alike. Sized by its [modifier].
 */
@Composable
fun Bone(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Padding.small),
) {
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = BONE_ALPHA),
            shape = shape,
        ),
    )
}

/**
 * A line of text in [style]: as tall as the line, so that the lines below it fall where the text's
 * would, with a bone as tall as its letters. Its width is its [modifier]'s, the whole width unless
 * that says otherwise.
 */
@Composable
fun TextBone(
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val density = LocalDensity.current
    val lineHeight = with(density) {
        (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.2f).toDp()
    }
    val letterHeight = with(density) { (style.fontSize * LETTER_HEIGHT).toDp() }

    Box(
        modifier = modifier.height(lineHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Bone(
            modifier = Modifier
                .fillMaxWidth()
                .height(letterHeight),
        )
    }
}

/**
 * A card, in the colour of Material's `Card`, which most of the app's lists are made of, and with
 * the corners the app gives its own. What it holds is up to the screen.
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Padding.large),
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.background(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = shape),
        content = content,
    )
}

/**
 * A person's card, as the app lists people: a row as tall as a text field, their picture, which
 * `ProfilePictureCard` draws within a [Padding.medium] margin, and their name, as wide as
 * [nameWidth] of what the row leaves it. [trailing] stands for what ends the row, if anything.
 */
@Composable
fun SkeletonPersonCard(
    nameWidth: Float,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    SkeletonCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBone()

            Box(modifier = Modifier.weight(1f)) {
                TextBone(
                    modifier = Modifier.fillMaxWidth(nameWidth),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            trailing()
        }
    }
}

/**
 * A person's picture as `ProfilePictureCard` draws it in a row: a circle as tall as the row, within
 * a [Padding.medium] margin.
 */
@Composable
fun AvatarBone(modifier: Modifier = Modifier) {
    Bone(
        modifier = modifier
            .fillMaxHeight()
            .padding(Padding.medium)
            .aspectRatio(1f),
        shape = CircleShape,
    )
}

/** A text field, as the app draws its own, empty. */
@Composable
fun FieldBone(modifier: Modifier = Modifier) {
    Bone(
        modifier = modifier
            .fillMaxWidth()
            .height(TextFieldDefaults.MinHeight),
        shape = RoundedCornerShape(Padding.large),
    )
}

/**
 * How wide the [index]th line of a list is, as a fraction of the room it has: lines of a few
 * lengths, in an order that does not repeat for a screenful, read as text where lines of one
 * length read as stripes.
 */
fun skeletonLineWidth(index: Int): Float = LINE_WIDTHS[index % LINE_WIDTHS.size]

private val LINE_WIDTHS = floatArrayOf(0.62f, 0.45f, 0.78f, 0.54f, 0.7f, 0.4f, 0.58f)

/** How faint a bone is: of the text colour, at this alpha. */
private const val BONE_ALPHA = 0.14f

/** How tall a text bone is, of its font size: about that of a line's capital letters. */
private const val LETTER_HEIGHT = 0.72f

/** How long the skeleton takes to fade in. */
private const val APPEARANCE_MILLIS = 300

/** How long each half of a pulse lasts, from full to faint or back. */
private const val PULSE_MILLIS = 900

/** How faint the skeleton is at the lowest of a pulse. */
private const val PULSE_LOWEST_ALPHA = 0.55f
