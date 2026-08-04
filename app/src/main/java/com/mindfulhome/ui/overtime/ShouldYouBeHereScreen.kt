package com.mindfulhome.ui.overtime
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mindfulhome.R

/**
 * Confrontation after Recents (or similar) returns to a restricted app while the session
 * timer is already expired. Not a timer gate and shares no state with focus/app gates —
 * green leaves; red opens the same expire→extend notification chat.
 */
@Composable
fun ShouldYouBeHereScreen(
    appLabel: String?,
    onLeave: () -> Unit,
    onNeedMoreTime: () -> Unit,
) {
    val subtitle = if (!appLabel.isNullOrBlank()) {
        "You're back in $appLabel after time ran out."
    } else {
        "You're back in a restricted app after time ran out."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.should_you_be_here),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onLeave,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF16A34A),
                contentColor = Color.White,
            ),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bird_free),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.i_ll_do_something_else),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onNeedMoreTime,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDC2626),
                contentColor = Color.White,
            ),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bird_caged_laptop),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.yeah_i_need_to_finish_this_thing),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
