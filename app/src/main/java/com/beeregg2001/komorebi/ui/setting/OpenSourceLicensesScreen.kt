package com.beeregg2001.komorebi.ui.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.*
import kotlinx.coroutines.launch

data class OssLibrary(
    val name: String,
    val author: String,
    val licenseName: String,
    val licenseText: String
)

val ossLibraries = listOf(
    OssLibrary(
        name = "tsreadex",
        author = "xtne6f",
        licenseName = "MIT License",
        licenseText = """
            MIT License

            Copyright (c) 2015 xtne6f

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent()
    ),
    OssLibrary(
        name = "aribb24.js",
        author = "xqq, monyone",
        licenseName = "MIT License",
        licenseText = """
            MIT License

            Copyright (c) 2017-2021 xqq
            Copyright (c) 2021 monyone

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent()
    ),
    OssLibrary(
        name = "KonomiTV (API Architecture)",
        author = "tsukumi",
        licenseName = "MIT License",
        licenseText = """
            MIT License

            Copyright (c) 2021-2026 tsukumi

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent()
    ),
    OssLibrary(
        name = "Mirakurun (API Backend)",
        author = "kanreisa",
        licenseName = "Apache License 2.0",
        licenseText = """
            Apache License, Version 2.0

            Copyright 2016- kanreisa

            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent()
    )
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    var selectedLib by remember { mutableStateOf(ossLibraries.first()) }
    val listFocusRequester = remember { FocusRequester() }
    val textFocusRequester = remember { FocusRequester() }

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(48.dp)) {
        // 左ペイン：ライブラリ一覧
        Column(modifier = Modifier.weight(0.35f).fillMaxHeight()) {
            Text(
                text = "オープンソースライセンス",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp, start = 16.dp)
            )

            TvLazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ossLibraries) { lib ->
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { textFocusRequester.requestFocus() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                isFocused = it.isFocused
                                if (it.isFocused) selectedLib = lib
                            }
                            .then(if (lib == ossLibraries.first()) Modifier.focusRequester(listFocusRequester) else Modifier),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White,
                            contentColor = Color.LightGray,
                            focusedContentColor = Color.Black
                        ),
                        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = lib.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = "by ${lib.author}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(48.dp))

        // 右ペイン：ライセンス本文（リモコンの上下でスクロール可能）
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(selectedLib) {
            scrollState.scrollTo(0)
        }

        Box(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight()
                .background(Color(0xFF1E1E1E), MaterialTheme.shapes.medium)
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value + 300) }
                                    true
                                }
                                Key.DirectionUp -> {
                                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.value - 300) }
                                    true
                                }
                                Key.DirectionLeft -> {
                                    listFocusRequester.requestFocus()
                                    true
                                }
                                Key.Back, Key.Escape -> {
                                    onBack()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .focusable()
                    .focusRequester(textFocusRequester)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = selectedLib.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = selectedLib.licenseName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                Text(
                    text = selectedLib.licenseText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray,
                    lineHeight = 28.sp
                )

                // スクロール限界時の余白
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }
}