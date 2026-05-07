package com.example.animalgobble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class AppScreen { TITLE, HOW_TO_PLAY, GAME }

enum class PieceSize(val order: Int) {
    SMALL(1), MEDIUM(2), LARGE(3)
}

enum class Player(
    val label: String,
    val color: Color,
    val emoji: Map<PieceSize, String>
) {
    ONE(
        "🐱 ネコチーム",
        Color(0xFFFF8F00),
        mapOf(PieceSize.SMALL to "🐱", PieceSize.MEDIUM to "🐈", PieceSize.LARGE to "🦁")
    ),
    TWO(
        "🐶 イヌチーム",
        Color(0xFF1565C0),
        mapOf(PieceSize.SMALL to "🐶", PieceSize.MEDIUM to "🐕", PieceSize.LARGE to "🐺")
    )
}

data class Piece(val player: Player, val size: PieceSize)

data class GameState(
    val board: List<List<List<Piece>>> = List(3) { List(3) { emptyList() } },
    val hand: Map<Player, Map<PieceSize, Int>> = mapOf(
        Player.ONE to mapOf(PieceSize.SMALL to 2, PieceSize.MEDIUM to 2, PieceSize.LARGE to 2),
        Player.TWO to mapOf(PieceSize.SMALL to 2, PieceSize.MEDIUM to 2, PieceSize.LARGE to 2)
    ),
    val currentPlayer: Player = Player.ONE,
    val winner: Player? = null,
    val selectedHandPiece: PieceSize? = null,
    val selectedBoardPos: Pair<Int, Int>? = null
)

// ---------------------------------------------------------------------------
// Game logic
// ---------------------------------------------------------------------------

fun checkWinner(board: List<List<List<Piece>>>): Player? {
    val lines = listOf(
        listOf(Pair(0,0), Pair(0,1), Pair(0,2)),
        listOf(Pair(1,0), Pair(1,1), Pair(1,2)),
        listOf(Pair(2,0), Pair(2,1), Pair(2,2)),
        listOf(Pair(0,0), Pair(1,0), Pair(2,0)),
        listOf(Pair(0,1), Pair(1,1), Pair(2,1)),
        listOf(Pair(0,2), Pair(1,2), Pair(2,2)),
        listOf(Pair(0,0), Pair(1,1), Pair(2,2)),
        listOf(Pair(0,2), Pair(1,1), Pair(2,0))
    )
    for (line in lines) {
        val tops = line.map { (r, c) -> board[r][c].lastOrNull() }
        if (tops.all { it != null && it.player == Player.ONE }) return Player.ONE
        if (tops.all { it != null && it.player == Player.TWO }) return Player.TWO
    }
    return null
}

fun placePiece(state: GameState, toRow: Int, toCol: Int): GameState {
    val fromBoard = state.selectedBoardPos
    val fromHandSize = state.selectedHandPiece

    val pieceToPlace: Piece = when {
        fromBoard != null -> {
            state.board[fromBoard.first][fromBoard.second].lastOrNull() ?: return state
        }
        fromHandSize != null -> {
            val count = state.hand[state.currentPlayer]?.get(fromHandSize) ?: 0
            if (count <= 0) return state
            Piece(state.currentPlayer, fromHandSize)
        }
        else -> return state
    }

    val topOfTarget = state.board[toRow][toCol].lastOrNull()
    if (topOfTarget != null && topOfTarget.size.order >= pieceToPlace.size.order) return state
    if (fromBoard != null && fromBoard.first == toRow && fromBoard.second == toCol) return state

    if (fromBoard != null) {
        val boardAfterLift = state.board.mapIndexed { r, row ->
            row.mapIndexed { c, stack ->
                if (r == fromBoard.first && c == fromBoard.second) stack.dropLast(1) else stack
            }
        }
        val revealedWinner = checkWinner(boardAfterLift)
        if (revealedWinner != null) {
            return state.copy(
                board = boardAfterLift,
                winner = revealedWinner,
                selectedHandPiece = null,
                selectedBoardPos = null
            )
        }
    }

    val newBoard = state.board.mapIndexed { r, row ->
        row.mapIndexed { c, stack ->
            when {
                r == fromBoard?.first && c == fromBoard.second -> stack.dropLast(1)
                r == toRow && c == toCol -> stack + pieceToPlace
                else -> stack
            }
        }
    }

    val newHand = if (fromHandSize != null) {
        state.hand.toMutableMap().also { h ->
            val playerHand = h[state.currentPlayer]!!.toMutableMap()
            playerHand[fromHandSize] = (playerHand[fromHandSize] ?: 0) - 1
            h[state.currentPlayer] = playerHand
        }
    } else state.hand

    val winner = checkWinner(newBoard)
    val nextPlayer = if (state.currentPlayer == Player.ONE) Player.TWO else Player.ONE

    return state.copy(
        board = newBoard,
        hand = newHand,
        currentPlayer = if (winner != null) state.currentPlayer else nextPlayer,
        winner = winner,
        selectedHandPiece = null,
        selectedBoardPos = null
    )
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F0E8)
                ) {
                    AppHost()
                }
            }
        }
    }
}

@Composable
fun AppHost() {
    var screen by remember { mutableStateOf(AppScreen.TITLE) }
    when (screen) {
        AppScreen.TITLE -> TitleScreen(
            onStart = { screen = AppScreen.GAME },
            onHowToPlay = { screen = AppScreen.HOW_TO_PLAY }
        )
        AppScreen.HOW_TO_PLAY -> HowToPlayScreen(
            onBack = { screen = AppScreen.TITLE }
        )
        AppScreen.GAME -> GameScreen(
            onBackToTitle = { screen = AppScreen.TITLE }
        )
    }
}

// ---------------------------------------------------------------------------
// Title screen
// ---------------------------------------------------------------------------

@Composable
fun TitleScreen(onStart: () -> Unit, onHowToPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🐾",
                fontSize = 56.sp
            )
            Text(
                text = "Animal Gobble",
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF4E342E),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "アニマルゴブル",
                fontSize = 15.sp,
                color = Color(0xFF8D6E63)
            )
        }

        // Animals
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🦁", fontSize = 52.sp)
                Text("🐈", fontSize = 36.sp)
                Text("🐱", fontSize = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text("🐱 ネコチーム", color = Color(0xFFFF8F00), fontWeight = FontWeight.Bold)
            }
            Text("VS", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color(0xFF8D6E63))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐺", fontSize = 52.sp)
                Text("🐕", fontSize = 36.sp)
                Text("🐶", fontSize = 24.sp)
                Spacer(Modifier.height(4.dp))
                Text("🐶 イヌチーム", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00))
            ) {
                Text("ゲームスタート", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onHowToPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4E342E)),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 1.5.dp
                )
            ) {
                Text("遺び方", fontSize = 16.sp)
            }
        }

        Text(
            text = "２人対戦・同一端末プレイ",
            fontSize = 12.sp,
            color = Color(0xFF8D6E63)
        )
    }
}

// ---------------------------------------------------------------------------
// How to play screen
// ---------------------------------------------------------------------------

@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "戻る",
                    tint = Color(0xFF4E342E)
                )
            }
            Text(
                text = "遺び方",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4E342E)
            )
        }

        HorizontalDivider(color = Color(0xFFD7CCC8))

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            RuleSection(
                emoji = "🎯",
                title = "ゲームの目的",
                body = "ネコチームとイヌチームの、2人対戦ボードゲームです。\n3×3のマスに自分の馨を広げて、縦・横・斜めのいずれか】3つ並べた方が勝ちです。"
            )

            RuleSection(
                emoji = "🐱",
                title = "馨について",
                body = "各チームにS（小）・M（中）＋L（大）の3サイズの馨がそれぞれ2個ずつ、合計6個あります。\n\n🐱 S ：小さなネコ（小）\n🐈 M ：中くらいのネコ（中）\n🦁 L ：大きなライオン（大）"
            )

            RuleSection(
                emoji = "💪",
                title = "馨の大小関係",
                body = "大きい馨は小さい馨の上に被せて隠すことができます。\n\nL > M > Sの順で大きいほど強いです。\n同じ大きさや小さい馨が被せることはできません。"
            )

            RuleSection(
                emoji = "🔄",
                title = "手番でできること",
                body = "一回の手番で、次のどちらか1つを選んで行動します。\n\n▶ 手持ちの馨を盤面に置く\n▶ 盤面上の自分の馨を持ち上げて別のマスへ移動する"
            )

            RuleSection(
                emoji = "⚠️",
                title = "リベールルール（重要）",
                body = "盤面上の馨を持ち上げた瞬間、隠れていた相手の馨が現れて相手の3並びが成立した場合→ その場で相手の勝ちです。\n\n持ち上げた馨でブロックしようとしても無効です。馨を動かす前によく考えましょう！",
                highlight = true
            )

            RuleSection(
                emoji = "📱",
                title = "対面プレイ",
                body = "同じスマホを1台でテーブルを挑んで対戦できます。\n画面上部のエリアは相手側（180°回転）、下部は自分側です。"
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00))
            ) {
                Text("タイトルに戻る", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun RuleSection(emoji: String, title: String, body: String, highlight: Boolean = false) {
    val bgColor = if (highlight) Color(0xFFFFF8E1) else Color.White
    val borderColor = if (highlight) Color(0xFFFFB300) else Color(0xFFEEEEEE)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (highlight) 1.5.dp else 1.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (highlight) Color(0xFFFFECB3) else Color(0xFFF5F0E8)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 20.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) Color(0xFFE65100) else Color(0xFF4E342E)
                )
                Text(
                    text = body,
                    fontSize = 13.sp,
                    color = Color(0xFF5D4037),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Game screen
// ---------------------------------------------------------------------------

@Composable
fun GameScreen(onBackToTitle: () -> Unit = {}) {
    var gameState by remember { mutableStateOf(GameState()) }

    if (gameState.winner != null) {
        WinnerDialog(
            winner = gameState.winner!!,
            onReset = { gameState = GameState() },
            onBackToTitle = onBackToTitle
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Player TWO area (top, rotated 180°)
        Column(
            modifier = Modifier.rotate(180f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerArea(
                player = Player.TWO,
                gameState = gameState,
                isCurrentPlayer = gameState.currentPlayer == Player.TWO,
                onHandPieceSelected = { size ->
                    if (gameState.currentPlayer == Player.TWO) {
                        gameState = gameState.copy(
                            selectedHandPiece = if (gameState.selectedHandPiece == size) null else size,
                            selectedBoardPos = null
                        )
                    }
                }
            )
        }

        // Board
        BoardGrid(
            gameState = gameState,
            onCellClick = { row, col ->
                if (gameState.winner != null) return@BoardGrid
                val topPiece = gameState.board[row][col].lastOrNull()
                when {
                    topPiece != null && topPiece.player == gameState.currentPlayer
                        && gameState.selectedHandPiece == null
                        && gameState.selectedBoardPos == null -> {
                        gameState = gameState.copy(selectedBoardPos = Pair(row, col))
                    }
                    gameState.selectedBoardPos == Pair(row, col) -> {
                        gameState = gameState.copy(selectedBoardPos = null)
                    }
                    gameState.selectedHandPiece != null || gameState.selectedBoardPos != null -> {
                        gameState = placePiece(gameState, row, col)
                    }
                }
            }
        )

        // Player ONE area (bottom)
        PlayerArea(
            player = Player.ONE,
            gameState = gameState,
            isCurrentPlayer = gameState.currentPlayer == Player.ONE,
            onHandPieceSelected = { size ->
                if (gameState.currentPlayer == Player.ONE) {
                    gameState = gameState.copy(
                        selectedHandPiece = if (gameState.selectedHandPiece == size) null else size,
                        selectedBoardPos = null
                    )
                }
            }
        )
    }
}

@Composable
fun PlayerArea(
    player: Player,
    gameState: GameState,
    isCurrentPlayer: Boolean,
    onHandPieceSelected: (PieceSize) -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isCurrentPlayer) player.color.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(300),
        label = "playerBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (isCurrentPlayer) "あなたのターンです" else "",
            color = player.color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = player.label,
            color = player.color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PieceSize.entries.forEach { size ->
                val count = gameState.hand[player]?.get(size) ?: 0
                HandPieceButton(
                    player = player,
                    size = size,
                    count = count,
                    isSelected = isCurrentPlayer && gameState.selectedHandPiece == size,
                    enabled = isCurrentPlayer && count > 0 && gameState.winner == null,
                    onClick = { onHandPieceSelected(size) }
                )
            }
        }
    }
}

@Composable
fun HandPieceButton(
    player: Player,
    size: PieceSize,
    count: Int,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val emojiSize = when (size) { PieceSize.SMALL -> 22.sp; PieceSize.MEDIUM -> 30.sp; PieceSize.LARGE -> 38.sp }
    val boxSize = when (size) { PieceSize.SMALL -> 52.dp; PieceSize.MEDIUM -> 62.dp; PieceSize.LARGE -> 72.dp }

    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) player.color.copy(alpha = 0.3f) else Color.White.copy(alpha = if (enabled) 1f else 0.4f))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) player.color else Color.Gray.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = player.emoji[size] ?: "", fontSize = emojiSize)
            Text(
                text = "×$count",
                fontSize = 11.sp,
                color = if (enabled) Color.DarkGray else Color.LightGray
            )
        }
    }
}

@Composable
fun BoardGrid(gameState: GameState, onCellClick: (Int, Int) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..2) {
                    BoardCell(
                        stack = gameState.board[row][col],
                        isSelected = gameState.selectedBoardPos == Pair(row, col),
                        onClick = { onCellClick(row, col) }
                    )
                }
            }
        }
    }
}

@Composable
fun BoardCell(stack: List<Piece>, isSelected: Boolean, onClick: () -> Unit) {
    val topPiece = stack.lastOrNull()
    val borderColor = when {
        isSelected -> Color(0xFFFFC107)
        topPiece != null -> topPiece.player.color.copy(alpha = 0.6f)
        else -> Color.Gray.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(
                width = if (isSelected) 3.dp else 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (topPiece != null) {
            val emojiSize = when (topPiece.size) {
                PieceSize.SMALL -> 28.sp; PieceSize.MEDIUM -> 38.sp; PieceSize.LARGE -> 52.sp
            }
            Text(text = topPiece.player.emoji[topPiece.size] ?: "", fontSize = emojiSize, textAlign = TextAlign.Center)
        }
        if (stack.size > 1) {
            Text(
                text = "${stack.size}",
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            )
        }
    }
}

@Composable
fun WinnerDialog(winner: Player, onReset: () -> Unit, onBackToTitle: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("🏆 ゲーム終了！", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${winner.label}\nの勝ち！",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = winner.color,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = winner.color)
                ) {
                    Text("もう一度プレイ", fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = onBackToTitle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("タイトルに戻る", fontSize = 14.sp)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TitleScreenPreview() {
    MaterialTheme { TitleScreen(onStart = {}, onHowToPlay = {}) }
}

@Preview(showBackground = true)
@Composable
fun HowToPlayScreenPreview() {
    MaterialTheme { HowToPlayScreen(onBack = {}) }
}
