//package com.example.komorebi.ui.epg
//
//import android.os.Build
//import androidx.annotation.RequiresApi
//import androidx.compose.foundation.layout.Box
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.focus.FocusRequester
//import com.example.komorebi.data.model.EpgProgram
//import com.example.komorebi.viewmodel.EpgUiState
//
//@RequiresApi(Build.VERSION_CODES.O)
//@Composable
//fun EpgNavigationContainer(uiState: EpgUiState.Success, logoUrls: List<String>) {
//    // 現在選択されている番組（nullなら番組表、値があれば詳細画面）
//    var selectedProgram by remember { mutableStateOf<EpgProgram?>(null) }
//
//    val topTabFocusRequester = remember { FocusRequester() }
//    val contentFocusRequester = remember { FocusRequester() }
//
//    // Androidの「戻る」ボタンを押した時の挙動
//    androidx.activity.compose.BackHandler(enabled = selectedProgram != null) {
//        selectedProgram = null
//    }
//
//    Box(modifier = Modifier.fillMaxSize()) {
//        if (selectedProgram == null) {
//            // --- 番組表表示 ---
//            ModernEpgCanvasEngine(
//                uiState = uiState,
//                logoUrls = logoUrls,
//                topTabFocusRequester = topTabFocusRequester,
//                contentFocusRequester = contentFocusRequester,
//                onProgramSelected = { program ->
//                    selectedProgram = program // 番組がクリックされたら状態を更新
//                }
//            )
//        } else {
//            // --- 詳細画面表示 ---
//            ProgramDetailScreen(
//                program = selectedProgram!!,
//                onPlayClick = { /* 視聴プレイヤーへ遷移 */ },
//                onRecordClick = { /* 録画予約APIを叩く */ },
//                onBackClick = { selectedProgram = null } // 戻るボタンで番組表へ
//            )
//        }
//    }
//}