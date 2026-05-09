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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class AppScreen { TITLE, HOW_TO_PLAY, GAME }

enum class PieceSize(val order: Int) { SMALL(1), MEDIUM(2), LARGE(3) }

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

data class Move(
    val fromBoardPos: Pair<Int, Int>? = null,
    val fromHandSize: PieceSize? = null,
    val toRow: Int,
    val toCol: Int
)

// ---------------------------------------------------------------------------
// Game logic
// ---------------------------------------------------------------------------

fun checkWinner(board: List<List<List<Piece>>>): Player? {
    val lines = listOf(
        listOf(0 to 0, 0 to 1, 0 to 2), listOf(1 to 0, 1 to 1, 1 to 2), listOf(2 to 0, 2 to 1, 2 to 2),
        listOf(0 to 0, 1 to 0, 2 to 0), listOf(0 to 1, 1 to 1, 2 to 1), listOf(0 to 2, 1 to 2, 2 to 2),
        listOf(0 to 0, 1 to 1, 2 to 2), listOf(0 to 2, 1 to 1, 2 to 0)
    )
    for (line in lines) {
        val tops = line.map { (r, c) -> board[r][c].lastOrNull() }
        if (tops.all { it?.player == Player.ONE }) return Player.ONE
        if (tops.all { it?.player == Player.TWO }) return Player.TWO
    }
    return null
}

fun placePiece(state: GameState, toRow: Int, toCol: Int): GameState {
    val fromBoard = state.selectedBoardPos
    val fromHandSize = state.selectedHandPiece

    val pieceToPlace: Piece = when {
        fromBoard != null -> state.board[fromBoard.first][fromBoard.second].lastOrNull() ?: return state
        fromHandSize != null -> {
            if ((state.hand[state.currentPlayer]?.get(fromHandSize) ?: 0) <= 0) return state
            Piece(state.currentPlayer, fromHandSize)
        }
        else -> return state
    }

    val topOfTarget = state.board[toRow][toCol].lastOrNull()
    if (topOfTarget != null && topOfTarget.size.order >= pieceToPlace.size.order) return state
    if (fromBoard != null && fromBoard == toRow to toCol) return state

    if (fromBoard != null) {
        val boardAfterLift = state.board.mapIndexed { r, row ->
            row.mapIndexed { c, stack ->
                if (r == fromBoard.first && c == fromBoard.second) stack.dropLast(1) else stack
            }
        }
        checkWinner(boardAfterLift)?.let { revealed ->
            return state.copy(board = boardAfterLift, winner = revealed, selectedHandPiece = null, selectedBoardPos = null)
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
            val ph = h[state.currentPlayer]!!.toMutableMap()
            ph[fromHandSize] = (ph[fromHandSize] ?: 0) - 1
            h[state.currentPlayer] = ph
        }
    } else state.hand

    val winner = checkWinner(newBoard)
    val next = if (state.currentPlayer == Player.ONE) Player.TWO else Player.ONE
    return state.copy(
        board = newBoard, hand = newHand,
        currentPlayer = if (winner != null) state.currentPlayer else next,
        winner = winner, selectedHandPiece = null, selectedBoardPos = null
    )
}

// ---------------------------------------------------------------------------
// CPU AI (minimax with alpha-beta pruning)
// ---------------------------------------------------------------------------

private val WIN_LINES = listOf(
    listOf(0 to 0, 0 to 1, 0 to 2), listOf(1 to 0, 1 to 1, 1 to 2), listOf(2 to 0, 2 to 1, 2 to 2),
    listOf(0 to 0, 1 to 0, 2 to 0), listOf(0 to 1, 1 to 1, 2 to 1), listOf(0 to 2, 1 to 2, 2 to 2),
    listOf(0 to 0, 1 to 1, 2 to 2), listOf(0 to 2, 1 to 1, 2 to 0)
)

fun generateMoves(state: GameState, player: Player): List<Move> {
    val moves = mutableListOf<Move>()
    for (size in PieceSize.entries) {
        if ((state.hand[player]?.get(size) ?: 0) <= 0) continue
        for (r in 0..2) for (c in 0..2) {
            val top = state.board[r][c].lastOrNull()
            if (top == null || top.size.order < size.order)
                moves.add(Move(fromHandSize = size, toRow = r, toCol = c))
        }
    }
    for (fr in 0..2) for (fc in 0..2) {
        val piece = state.board[fr][fc].lastOrNull() ?: continue
        if (piece.player != player) continue
        for (tr in 0..2) for (tc in 0..2) {
            if (fr == tr && fc == tc) continue
            val top = state.board[tr][tc].lastOrNull()
            if (top == null || top.size.order < piece.size.order)
                moves.add(Move(fromBoardPos = fr to fc, toRow = tr, toCol = tc))
        }
    }
    return moves
}

fun applyMove(state: GameState, move: Move): GameState =
    placePiece(
        state.copy(selectedHandPiece = move.fromHandSize, selectedBoardPos = move.fromBoardPos),
        move.toRow, move.toCol
    )

fun evaluateBoard(board: List<List<List<Piece>>>, cpu: Player): Int {
    val human = if (cpu == Player.ONE) Player.TWO else Player.ONE
    var score = 0
    for (line in WIN_LINES) {
        val tops = line.map { (r, c) -> board[r][c].lastOrNull() }
        val cc = tops.count { it?.player == cpu }
        val hc = tops.count { it?.player == human }
        if (hc == 0) score += if (cc == 2) 4 else if (cc == 1) 1 else 0
        if (cc == 0) score -= if (hc == 2) 4 else if (hc == 1) 1 else 0
    }
    return score
}

fun minimax(state: GameState, depth: Int, alpha: Int, beta: Int, cpu: Player): Int {
    state.winner?.let { w -> return if (w == cpu) 1000 + depth else -1000 - depth }
    if (depth == 0) return evaluateBoard(state.board, cpu)
    val moves = generateMoves(state, state.currentPlayer)
    if (moves.isEmpty()) return 0
    return if (state.currentPlayer == cpu) {
        var best = -9999; var a = alpha
        for (m in moves) {
            best = maxOf(best, minimax(applyMove(state, m), depth - 1, a, beta, cpu))
            a = maxOf(a, best); if (beta <= a) break
        }
        best
    } else {
        var best = 9999; var b = beta
        for (m in moves) {
            best = minOf(best, minimax(applyMove(state, m), depth - 1, alpha, b, cpu))
            b = minOf(b, best); if (b <= alpha) break
        }
        best
    }
}

fun getBestMove(state: GameState, cpu: Player): Move? =
    generateMoves(state, cpu).maxByOrNull { m ->
        minimax(applyMove(state, m), depth = 3, -9999, 9999, cpu)
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
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F0E8)) {
                    AppHost()
                }
            }
        }
    }
}

@Composable
fun AppHost() {
    var screen by remember { mutableStateOf(AppScreen.TITLE) }
    var isCpuMode by remember { mutableStateOf(false) }
    when (screen) {
        AppScreen.TITLE -> TitleScreen(
            onStart2P = { isCpuMode = false; screen = AppScreen.GAME },
            onStartCpu = { isCpuMode = true; screen = AppScreen.GAME },
            onHowToPlay = { screen = AppScreen.HOW_TO_PLAY }
        )
        AppScreen.HOW_TO_PLAY -> HowToPlayScreen(onBack = { screen = AppScreen.TITLE })
        AppScreen.GAME -> GameScreen(isCpuMode = isCpuMode, onBackToTitle = { screen = AppScreen.TITLE })
    }
}

// ---------------------------------------------------------------------------
// Title screen
// ---------------------------------------------------------------------------

@Composable
fun TitleScreen(onStart2P: () -> Unit, onStartCpu: () -> Unit, onHowToPlay: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // App title block
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🐱", fontSize = 36.sp)
                Text("🐶", fontSize = 36.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "ねこVSいぬ！",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF4E342E),
                textAlign = TextAlign.Center
            )
            Text(
                text = "コマかくしバトル",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8D6E63)
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFEFEBE9)
            ) {
                Text(
                    "Animal Gobble",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = Color(0xFF8D6E63)
                )
            }
        }

        // Team preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🦁", fontSize = 44.sp)
                Text("🐈", fontSize = 30.sp)
                Text("🐱", fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text("🐱 ネコチーム", color = Color(0xFFFF8F00), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✨VS✨", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF8D6E63))
                Spacer(Modifier.height(4.dp))
                Text("おおきいこまで", fontSize = 11.sp, color = Color(0xFF8D6E63))
                Text("かくせ！", fontSize = 11.sp, color = Color(0xFF8D6E63))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐺", fontSize = 44.sp)
                Text("🐕", fontSize = 30.sp)
                Text("🐶", fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text("🐶 イヌチーム", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // Buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onStart2P,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00))
            ) {
                Text("👥 2人でやる！", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Button(
                onClick = onStartCpu,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("🤖 CPUとたたかう！", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            OutlinedButton(
                onClick = onHowToPlay,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4E342E)),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.5.dp)
            ) {
                Text("❓ あそびかた", fontSize = 15.sp)
            }
        }

        Text(
            "スマホ1台で 2人がたのしめる！🎉",
            fontSize = 12.sp, color = Color(0xFF8D6E63)
        )
    }
}

// ---------------------------------------------------------------------------
// How to play screen
// ---------------------------------------------------------------------------

@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ほどく", tint = Color(0xFF4E342E))
            }
            Text("あそびかた", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4E342E))
        }
        HorizontalDivider(color = Color(0xFFD7CCC8))
        Column(
            modifier = Modifier.verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            RuleSection(
                emoji = "🎯",
                title = "どうやって かつの？",
                body = "ネコチーム ― イヌチームの２人対戦ゲームだよ！\n\n3×3のマスに自分のこまをならべて、みっつが\n・たて\n・よこ\n・ななめ\nに3つつながったら 🏆 かちだよ！"
            )
            RuleSection(
                emoji = "🐱",
                title = "こまのせつめい",
                body = "それぞれのチームに、\n小・中・大の3しゅるいのこまが 2まいずつあるよ。\n\n🐱 小：ちびネコ\n🐈 中：フツウネコ\n🦁 大：ライオン"
            )
            RuleSection(
                emoji = "💪",
                title = "おおきいこまはつよい！",
                body = "おおきいこまは、ちいさいこまの上にかぶせて かくす ことができるよ！\n\n大 > 中 > 小 のじゅんでつよいよ。\nおなじおおきさや、ちいさいこまをかぶせることはできないよ。"
            )
            RuleSection(
                emoji = "🔄",
                title = "じぶんのばんで できること",
                body = "1かいのばんで、つぎのどちらかひとつをやるよ。\n\n▶ てもちのこまをマスにおく\n▶ はんのじぶんのこまをもちあげて べつのマスにうごかす"
            )
            RuleSection(
                emoji = "⚠️",
                title = "とくべつのルール！",
                body = "はんのこまをもちあげたとき、かくれていたあいてのこまがでてきて あいての3つなぎができたら\n→ そこで あいてのかちになるよ！\n\nもちあげたこまでブロックしようとしても ダメだよ。\nこまをうごかすまえに、よーくかんがえてね！",
                highlight = true
            )
            RuleSection(
                emoji = "📱",
                title = "おもたのまわりプレイ",
                body = "スマホ1台で、テーブルをはさんで 2人でたたかえるよ。\n画面の上があいてのエリア（さかさまひょうじ）、下が自分のエリアだよ。"
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00))
            ) { Text("タイトルにほどく", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun RuleSection(emoji: String, title: String, body: String, highlight: Boolean = false) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlight) Color(0xFFFFF8E1) else Color.White),
        border = CardDefaults.outlinedCardBorder().copy(width = if (highlight) 1.5.dp else 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (highlight) Color(0xFFFFECB3) else Color(0xFFF5F0E8)),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 20.sp) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (highlight) Color(0xFFE65100) else Color(0xFF4E342E)
                )
                Text(body, fontSize = 13.sp, color = Color(0xFF5D4037), lineHeight = 20.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Game screen
// ---------------------------------------------------------------------------

@Composable
fun GameScreen(onBackToTitle: () -> Unit = {}, isCpuMode: Boolean = false) {
    val cpuPlayer = if (isCpuMode) Player.TWO else null
    var gameState by remember { mutableStateOf(GameState()) }
    var cpuThinking by remember { mutableStateOf(false) }

    LaunchedEffect(gameState.currentPlayer, gameState.winner) {
        if (cpuPlayer != null && gameState.winner == null && gameState.currentPlayer == cpuPlayer) {
            cpuThinking = true
            delay(500)
            val move = withContext(Dispatchers.Default) { getBestMove(gameState, cpuPlayer) }
            move?.let { gameState = applyMove(gameState, it) }
            cpuThinking = false
        }
    }

    if (gameState.winner != null) {
        WinnerDialog(
            winner = gameState.winner!!,
            isCpuMode = isCpuMode,
            onReset = { gameState = GameState() },
            onBackToTitle = onBackToTitle
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = if (isCpuMode) Modifier else Modifier.rotate(180f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (cpuThinking) {
                Text("🤖 かんがえてる…", color = Player.TWO.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            PlayerArea(
                player = Player.TWO,
                gameState = gameState,
                isCurrentPlayer = gameState.currentPlayer == Player.TWO,
                interactionEnabled = !isCpuMode,
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
                if (gameState.winner != null || cpuThinking) return@BoardGrid
                if (isCpuMode && gameState.currentPlayer == cpuPlayer) return@BoardGrid
                val topPiece = gameState.board[row][col].lastOrNull()
                when {
                    topPiece != null && topPiece.player == gameState.currentPlayer
                        && gameState.selectedHandPiece == null && gameState.selectedBoardPos == null ->
                        gameState = gameState.copy(selectedBoardPos = row to col)
                    gameState.selectedBoardPos == row to col ->
                        gameState = gameState.copy(selectedBoardPos = null)
                    gameState.selectedHandPiece != null || gameState.selectedBoardPos != null ->
                        gameState = placePiece(gameState, row, col)
                }
            }
        )

        PlayerArea(
            player = Player.ONE,
            gameState = gameState,
            isCurrentPlayer = gameState.currentPlayer == Player.ONE,
            interactionEnabled = !cpuThinking,
            onHandPieceSelected = { size ->
                if (gameState.currentPlayer == Player.ONE && !cpuThinking) {
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
    interactionEnabled: Boolean = true,
    onHandPieceSelected: (PieceSize) -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isCurrentPlayer) player.color.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(300), label = "playerBg"
    )
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bgColor).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (isCurrentPlayer) "あなたのバンだよ！" else "",
            color = player.color, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        Text(player.label, color = player.color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PieceSize.entries.forEach { size ->
                HandPieceButton(
                    player = player, size = size,
                    count = gameState.hand[player]?.get(size) ?: 0,
                    isSelected = isCurrentPlayer && gameState.selectedHandPiece == size,
                    enabled = interactionEnabled && isCurrentPlayer
                        && (gameState.hand[player]?.get(size) ?: 0) > 0
                        && gameState.winner == null,
                    onClick = { onHandPieceSelected(size) }
                )
            }
        }
    }
}

@Composable
fun HandPieceButton(
    player: Player, size: PieceSize, count: Int,
    isSelected: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    val emojiSize = when (size) { PieceSize.SMALL -> 22.sp; PieceSize.MEDIUM -> 30.sp; PieceSize.LARGE -> 38.sp }
    val boxSize = when (size) { PieceSize.SMALL -> 52.dp; PieceSize.MEDIUM -> 62.dp; PieceSize.LARGE -> 72.dp }
    Box(
        modifier = Modifier
            .size(boxSize).clip(RoundedCornerShape(10.dp))
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
            Text(player.emoji[size] ?: "", fontSize = emojiSize)
            Text("×$count", fontSize = 11.sp, color = if (enabled) Color.DarkGray else Color.LightGray)
        }
    }
}

@Composable
fun BoardGrid(gameState: GameState, onCellClick: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..2) {
                    BoardCell(
                        stack = gameState.board[row][col],
                        isSelected = gameState.selectedBoardPos == row to col,
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
    Box(
        modifier = Modifier
            .size(100.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(
                width = if (isSelected) 3.dp else 1.5.dp,
                color = when {
                    isSelected -> Color(0xFFFFC107)
                    topPiece != null -> topPiece.player.color.copy(alpha = 0.6f)
                    else -> Color.Gray.copy(alpha = 0.3f)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (topPiece != null) {
            Text(
                text = topPiece.player.emoji[topPiece.size] ?: "",
                fontSize = when (topPiece.size) { PieceSize.SMALL -> 28.sp; PieceSize.MEDIUM -> 38.sp; PieceSize.LARGE -> 52.sp },
                textAlign = TextAlign.Center
            )
        }
        if (stack.size > 1) {
            Text("${stack.size}", fontSize = 10.sp, color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }
}

@Composable
fun WinnerDialog(winner: Player, isCpuMode: Boolean, onReset: () -> Unit, onBackToTitle: () -> Unit) {
    val isCpuWon = isCpuMode && winner == Player.TWO
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
                Text(
                    if (isCpuWon) "🤖 CPUのかち！" else "🏆 ゲームおわり！",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isCpuWon) "もういっかい ちゃれんじゃない！" else "${winner.label}\nのかち！",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = winner.color, textAlign = TextAlign.Center
                )
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = winner.color)
                ) { Text("もういっかい！", fontSize = 16.sp) }
                OutlinedButton(
                    onClick = onBackToTitle,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                ) { Text("タイトルにほどく", fontSize = 14.sp) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TitleScreenPreview() {
    MaterialTheme { TitleScreen(onStart2P = {}, onStartCpu = {}, onHowToPlay = {}) }
}

@Preview(showBackground = true)
@Composable
fun HowToPlayScreenPreview() {
    MaterialTheme { HowToPlayScreen(onBack = {}) }
}
