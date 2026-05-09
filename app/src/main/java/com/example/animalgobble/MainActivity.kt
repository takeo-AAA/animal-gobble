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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

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

enum class GameMode { TWO_PLAYER, VS_CPU }

enum class Difficulty(val label: String) {
    EASY("かんたん"),
    NORMAL("ふつう"),
    HARD("むずかしい")
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
    val fromBoard: Pair<Int, Int>? = state.selectedBoardPos
    val fromHandSize: PieceSize? = state.selectedHandPiece

    val pieceToPlace: Piece = when {
        fromBoard != null -> {
            val stack = state.board[fromBoard.first][fromBoard.second]
            stack.lastOrNull() ?: return state
        }
        fromHandSize != null -> {
            val count = state.hand[state.currentPlayer]?.get(fromHandSize) ?: 0
            if (count <= 0) return state
            Piece(state.currentPlayer, fromHandSize)
        }
        else -> return state
    }

    val targetStack = state.board[toRow][toCol]
    val topOfTarget = targetStack.lastOrNull()
    if (topOfTarget != null && topOfTarget.size.order >= pieceToPlace.size.order) return state
    if (fromBoard != null && fromBoard.first == toRow && fromBoard.second == toCol) return state

    if (fromBoard != null) {
        val boardAfterLift = state.board.mapIndexed { r, rowList ->
            rowList.mapIndexed { c, stack ->
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

    val newBoard = state.board.mapIndexed { r, rowList ->
        rowList.mapIndexed { c, stack ->
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
    } else {
        state.hand
    }

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
// CPU AI
// ---------------------------------------------------------------------------

data class AiMove(
    val fromHand: PieceSize? = null,
    val fromBoard: Pair<Int, Int>? = null,
    val toRow: Int,
    val toCol: Int
)

fun allValidMoves(state: GameState, player: Player): List<AiMove> {
    val moves = mutableListOf<AiMove>()
    val hand = state.hand[player] ?: return moves
    for (size in PieceSize.entries) {
        if ((hand[size] ?: 0) <= 0) continue
        for (r in 0..2) for (c in 0..2) {
            val top = state.board[r][c].lastOrNull()
            if (top == null || top.size.order < size.order)
                moves += AiMove(fromHand = size, toRow = r, toCol = c)
        }
    }
    for (fr in 0..2) for (fc in 0..2) {
        val top = state.board[fr][fc].lastOrNull() ?: continue
        if (top.player != player) continue
        for (tr in 0..2) for (tc in 0..2) {
            if (tr == fr && tc == fc) continue
            val tTop = state.board[tr][tc].lastOrNull()
            if (tTop == null || tTop.size.order < top.size.order)
                moves += AiMove(fromBoard = Pair(fr, fc), toRow = tr, toCol = tc)
        }
    }
    return moves
}

private fun applyAiMove(state: GameState, move: AiMove, player: Player): GameState {
    val s = state.copy(
        currentPlayer = player,
        selectedHandPiece = move.fromHand,
        selectedBoardPos = move.fromBoard
    )
    return placePiece(s, move.toRow, move.toCol)
}

private fun evaluateBoard(state: GameState): Int {
    val lines = listOf(
        listOf(0 to 0, 0 to 1, 0 to 2), listOf(1 to 0, 1 to 1, 1 to 2), listOf(2 to 0, 2 to 1, 2 to 2),
        listOf(0 to 0, 1 to 0, 2 to 0), listOf(0 to 1, 1 to 1, 2 to 1), listOf(0 to 2, 1 to 2, 2 to 2),
        listOf(0 to 0, 1 to 1, 2 to 2), listOf(0 to 2, 1 to 1, 2 to 0)
    )
    var score = 0
    for (line in lines) {
        val tops = line.map { (r, c) -> state.board[r][c].lastOrNull() }
        val twoCount = tops.count { it?.player == Player.TWO }
        val oneCount = tops.count { it?.player == Player.ONE }
        if (oneCount == 0) score += when (twoCount) { 1 -> 1; 2 -> 10; else -> 0 }
        if (twoCount == 0) score -= when (oneCount) { 1 -> 1; 2 -> 10; else -> 0 }
    }
    val centerTop = state.board[1][1].lastOrNull()
    if (centerTop?.player == Player.TWO) score += 3
    else if (centerTop?.player == Player.ONE) score -= 3
    return score
}

private fun minimax(state: GameState, depth: Int, isMaximizing: Boolean, alpha: Int, beta: Int): Int {
    if (state.winner == Player.TWO) return 1000 + depth
    if (state.winner == Player.ONE) return -1000 - depth
    if (depth == 0) return evaluateBoard(state)
    val player = if (isMaximizing) Player.TWO else Player.ONE
    val moves = allValidMoves(state, player)
    if (moves.isEmpty()) return evaluateBoard(state)
    var a = alpha; var b = beta
    return if (isMaximizing) {
        var best = Int.MIN_VALUE
        for (m in moves) {
            best = maxOf(best, minimax(applyAiMove(state, m, player), depth - 1, false, a, b))
            a = maxOf(a, best)
            if (b <= a) break
        }
        best
    } else {
        var best = Int.MAX_VALUE
        for (m in moves) {
            best = minOf(best, minimax(applyAiMove(state, m, player), depth - 1, true, a, b))
            b = minOf(b, best)
            if (b <= a) break
        }
        best
    }
}

fun cpuBestMove(state: GameState, difficulty: Difficulty): AiMove? {
    val moves = allValidMoves(state, Player.TWO)
    if (moves.isEmpty()) return null
    return when (difficulty) {
        Difficulty.EASY -> moves.random()
        Difficulty.NORMAL -> {
            moves.firstOrNull { applyAiMove(state, it, Player.TWO).winner == Player.TWO }
                ?: run {
                    val humanState = state.copy(currentPlayer = Player.ONE, selectedHandPiece = null, selectedBoardPos = null)
                    val blockCells = allValidMoves(humanState, Player.ONE)
                        .filter { applyAiMove(humanState, it, Player.ONE).winner == Player.ONE }
                        .map { it.toRow to it.toCol }.toSet()
                    if (blockCells.isNotEmpty())
                        moves.firstOrNull { (it.toRow to it.toCol) in blockCells }
                    else null
                }
                ?: moves.random()
        }
        Difficulty.HARD -> {
            val immediateWin = moves.firstOrNull { applyAiMove(state, it, Player.TWO).winner == Player.TWO }
            if (immediateWin != null) return immediateWin
            var bestScore = Int.MIN_VALUE
            val bestMoves = mutableListOf<AiMove>()
            for (m in moves) {
                val score = minimax(applyAiMove(state, m, Player.TWO), 3, false, Int.MIN_VALUE, Int.MAX_VALUE)
                when {
                    score > bestScore -> { bestScore = score; bestMoves.clear(); bestMoves.add(m) }
                    score == bestScore -> bestMoves.add(m)
                }
            }
            bestMoves.random()
        }
    }
}

// ---------------------------------------------------------------------------
// UI
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
                    AnimalGobbleApp()
                }
            }
        }
    }
}

@Composable
fun AnimalGobbleApp() {
    var screen by remember { mutableStateOf("title") }
    var gameMode by remember { mutableStateOf(GameMode.TWO_PLAYER) }
    var difficulty by remember { mutableStateOf(Difficulty.NORMAL) }

    when (screen) {
        "title" -> TitleScreen(
            onStartTwoPlayer = { gameMode = GameMode.TWO_PLAYER; screen = "game" },
            onStartVsCpu = { diff -> gameMode = GameMode.VS_CPU; difficulty = diff; screen = "game" }
        )
        "game" -> GameScreen(
            gameMode = gameMode,
            difficulty = difficulty,
            onQuit = { screen = "title" }
        )
    }
}

@Composable
fun TitleScreen(onStartTwoPlayer: () -> Unit, onStartVsCpu: (Difficulty) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🐱 vs 🐶", fontSize = 52.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "ゴブレットゴブラーズ",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onStartTwoPlayer,
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("👫 2人プレイ", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(Modifier.height(36.dp))

        Text(
            "🤖 CPUと対戦",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.DarkGray
        )
        Spacer(Modifier.height(12.dp))

        val diffColors = listOf(Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFFEF5350))
        val diffEmoji = listOf("😊", "🤔", "😤")
        Difficulty.entries.forEachIndexed { i, diff ->
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onStartVsCpu(diff) },
                modifier = Modifier.fillMaxWidth(0.8f),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = diffColors[i])
            ) {
                Text("${diffEmoji[i]} ${diff.label}", fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun GameScreen(
    gameMode: GameMode = GameMode.TWO_PLAYER,
    difficulty: Difficulty = Difficulty.NORMAL,
    onQuit: () -> Unit = {}
) {
    var gameState by remember { mutableStateOf(GameState()) }
    var showQuitConfirm by remember { mutableStateOf(false) }
    val isCpuMode = gameMode == GameMode.VS_CPU
    val isCpuTurn = isCpuMode
        && gameState.currentPlayer == Player.TWO
        && gameState.winner == null
        && !showQuitConfirm

    if (isCpuTurn) {
        LaunchedEffect(gameState) {
            delay(700L)
            val move = withContext(Dispatchers.Default) { cpuBestMove(gameState, difficulty) }
            if (move != null) {
                val s = gameState.copy(selectedHandPiece = move.fromHand, selectedBoardPos = move.fromBoard)
                gameState = placePiece(s, move.toRow, move.toCol)
            }
        }
    }

    if (showQuitConfirm) {
        AlertDialog(
            onDismissRequest = { showQuitConfirm = false },
            title = { Text("かくにんする？", fontWeight = FontWeight.Bold) },
            text = { Text("ほんとうにやめる？") },
            confirmButton = {
                TextButton(onClick = { showQuitConfirm = false; onQuit() }) {
                    Text("やめる", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitConfirm = false }) { Text("つづける") }
            }
        )
    }

    if (gameState.winner != null) {
        WinnerDialog(
            winner = gameState.winner!!,
            isCpuMode = isCpuMode,
            onReset = { gameState = GameState() },
            onQuit = onQuit
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCpuMode) {
                Text(
                    text = "CPU: ${difficulty.label}",
                    color = Player.TWO.color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (gameState.winner == null) {
                TextButton(onClick = { showQuitConfirm = true }) {
                    Text("🏠 やめる", color = Color.Gray)
                }
            }
        }

        Column(modifier = Modifier.rotate(180f), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerArea(
                player = Player.TWO,
                gameState = gameState,
                isCurrentPlayer = gameState.currentPlayer == Player.TWO,
                isCpu = isCpuMode,
                isCpuThinking = isCpuTurn,
                onHandPieceSelected = { size ->
                    if (!isCpuMode && gameState.currentPlayer == Player.TWO) {
                        gameState = gameState.copy(
                            selectedHandPiece = if (gameState.selectedHandPiece == size) null else size,
                            selectedBoardPos = null
                        )
                    }
                }
            )
        }

        BoardGrid(
            gameState = gameState,
            onCellClick = { row, col ->
                if (gameState.winner != null || isCpuTurn) return@BoardGrid
                val stack = gameState.board[row][col]
                val topPiece = stack.lastOrNull()
                when {
                    topPiece != null
                        && topPiece.player == gameState.currentPlayer
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

        PlayerArea(
            player = Player.ONE,
            gameState = gameState,
            isCurrentPlayer = gameState.currentPlayer == Player.ONE,
            isCpu = false,
            isCpuThinking = false,
            onHandPieceSelected = { size ->
                if (gameState.currentPlayer == Player.ONE && !isCpuTurn) {
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
    isCpu: Boolean = false,
    isCpuThinking: Boolean = false,
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
        val turnText = when {
            isCpuThinking -> "CPU思考中... 🤔"
            isCurrentPlayer && isCpu -> "CPUのターン"
            isCurrentPlayer -> "あなたのターンです"
            else -> ""
        }
        Text(text = turnText, color = player.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)

        Text(
            text = if (isCpu) "🤖 CPU" else player.label,
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
                val isSelected = isCurrentPlayer && !isCpu && gameState.selectedHandPiece == size
                HandPieceButton(
                    player = player,
                    size = size,
                    count = count,
                    isSelected = isSelected,
                    enabled = !isCpu && isCurrentPlayer && count > 0 && gameState.winner == null,
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
    val emojiSize = when (size) {
        PieceSize.SMALL -> 22.sp
        PieceSize.MEDIUM -> 30.sp
        PieceSize.LARGE -> 38.sp
    }
    val boxSize = when (size) {
        PieceSize.SMALL -> 52.dp
        PieceSize.MEDIUM -> 62.dp
        PieceSize.LARGE -> 72.dp
    }

    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) player.color.copy(alpha = 0.3f)
                else Color.White.copy(alpha = if (enabled) 1f else 0.4f)
            )
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
                PieceSize.SMALL -> 28.sp
                PieceSize.MEDIUM -> 38.sp
                PieceSize.LARGE -> 52.sp
            }
            Text(
                text = topPiece.player.emoji[topPiece.size] ?: "",
                fontSize = emojiSize,
                textAlign = TextAlign.Center
            )
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
fun WinnerDialog(
    winner: Player,
    isCpuMode: Boolean = false,
    onReset: () -> Unit,
    onQuit: () -> Unit = {}
) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("🏆 ゲーム終了！", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val winText = if (isCpuMode) {
                    if (winner == Player.ONE) "あなたの勝ち！
🎉" else "CPUの勝ち！
😔"
                } else {
                    "${winner.label}\nの勝ち！"
                }
                Text(
                    text = winText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = winner.color,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = winner.color)
                ) {
                    Text("もう一度プレイ", fontSize = 16.sp)
                }
                TextButton(onClick = onQuit) {
                    Text("タイトルに戻る", color = Color.Gray)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GameScreenPreview() {
    MaterialTheme {
        GameScreen()
    }
}
