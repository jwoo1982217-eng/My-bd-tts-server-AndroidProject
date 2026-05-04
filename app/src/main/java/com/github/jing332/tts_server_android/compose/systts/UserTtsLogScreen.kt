package com.github.jing332.tts_server_android.compose.systts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.nav.NavTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserTtsLogScreen(
    vm: UserTtsLogViewModel = viewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            NavTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "使用者日志",
                            textAlign = TextAlign.Center
                        )

                        SelectionContainer {
                            Text(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(2.dp),
                                text = vm.logDir(),
                                style = MaterialTheme.typography.bodySmall,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            stringResource(id = R.string.clear_log)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LogScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
            list = vm.logs
        )
    }
}