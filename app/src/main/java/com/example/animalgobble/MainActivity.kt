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
        // rows
        listOf(Pair(0,0), Pair(0,1), Pair(0,2)),
        listOf(Pair(1,0), Pair(1,1), Pair(1,2)),
        listOf(Pair(2,0), Pair(2,1), Pair(2,2)),
        // cols
        listOf(Pair(0,0), Pair(1,0), Pair(2,0)),
        listOf(Pair(0,1), Pair(1,1), Pair(2,1)),
        listOf(Pair(0,2), Pair(1,2), Pair(2,2)),
        // diagonals
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

fun placePiece(
    state: GameState,
    toRow: Int,
    toCol: Int
): GameState {
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

    // Reveal rule: check if lifting the piece exposes opponent's win
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

    // Apply the move
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
                    GameScreen()
                }
            }
        }
    }
}

@Composable
fun GameScreen() {
    var gameState by remember { mutableStateOf(GameState()) }

    if (gameState.winner != null) {
        WinnerDialog(
            winner = gameState.winner!!,
            onReset = { gameState = GameState() }
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
                    gameState = if (gameState.currentPlayer == Player.TWO) {
                        gameState.copy(
                            selectedHandPiece = if (gameState.selectedHandPiece == size) null else size,
                            selectedBoardPos = null
                        )
                    } else gameState
                }
            )
        }

        // Board
        BoardGrid(
            gameState = gameState,
            onCellClick = { row, col ->
                if (gameState.winner != null) return@BoardGrid
                val stack = gameState.board[row][col]
                val topPiece = stack.lastOrNull()

                when {
                    // Tap own board piece to select it
                    topPiece != null && topPiece.player == gameState.currentPlayer
                        && gameState.selectedHandPiece == null
                        && gameState.selectedBoardPos == null -> {
                        gameState = gameState.copy(selectedBoardPos = Pair(row, col))
                    }
                    // Deselect if tapping the already-selected board cell
                    gameState.selectedBoardPos == Pair(row, col) -> {
                        gameState = gameState.copy(selectedBoardPos = null)
                    }
                    // Place if something is selected
                    gameState.selectedHandPiece != null || gameState.selectedBoardPos != null -> {
                        gameState = placePiece(gameState, row, col)
                    }
                }
            }
        )

        // Player ONE area (bottom, normal orientation)
        PlayerArea(
            player = Player.ONE,
            gameState = gameState,
            isCurrentPlayer = gameState.currentPlayer == Player.ONE,
            onHandPieceSelected = { size ->
                gameState = if (gameState.currentPlayer == Player.ONE) {
                    gameState.copy(
                        selectedHandPiece = if (gameState.selectedHandPiece == size) null else size,
                        selectedBoardPos = null
                    )
                } else gameState
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
        // Turn indicator
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

        // Hand pieces
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PieceSize.entries.forEach { size ->
                val count = gameState.hand[player]?.get(size) ?: 0
                val isSelected = isCurrentPlayer && gameState.selectedHandPiece == size
                HandPieceButton(
                    player = player,
                    size = size,
                    count = count,
                    isSelected = isSelected,
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
            Text(
                text = player.emoji[size] ?: "",
                fontSize = emojiSize
            )
            Text(
                text = "×$count",
                fontSize = 11.sp,
                color = if (enabled) Color.DarkGray else Color.LightGray
            )
        }
    }
}

@Composable
fun BoardGrid(
    gameState: GameState,
    onCellClick: (Int, Int) -> Unit
) {
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
fun BoardCell(
    stack: List<Piece>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
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

        // Stack depth indicator (bottom-right)
        if (stack.size > 1) {
            Text(
                text = "${stack.size}",
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun WinnerDialog(winner: Player, onReset: () -> Unit) {
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
                Text(
                    text = "🏆 ゲーム終了！",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${winner.label}\nの勝ち！",
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
