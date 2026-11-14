package com.octave.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import java.io.File

// Colors follow the light/dark theme
private val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val Cobalt: Color @Composable get() = MaterialTheme.colorScheme.primary
private val BarColor = Color(0xFF3D4DFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OctaveApp() {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFF8F9AFF), background = Color(0xFF0F1020), surface = Color(0xFF0F1020),
            onBackground = Color(0xFFEDEDFA), onSurface = Color(0xFFEDEDFA),
            onSurfaceVariant = Color(0xFFEDEDFA).copy(alpha = 0.6f)
        ) else lightColorScheme(
            primary = Color(0xFF3D4DFF), background = Color(0xFFF2F1FA), surface = Color(0xFFF2F1FA),
            onBackground = Color(0xFF14142B), onSurface = Color(0xFF14142B),
            onSurfaceVariant = Color(0xFF14142B).copy(alpha = 0.6f)
        )
    ) {
        val ctx = LocalContext.current
        val library = remember { Library(ctx) }
        val songs = remember { mutableStateListOf<Song>().apply { addAll(library.load()) } }
        val playlists = remember { mutableStateListOf<Playlist>().apply { addAll(library.loadPlaylists()) } }

        var tab by remember { mutableStateOf("All") }
        var query by remember { mutableStateOf("") }
        var searching by remember { mutableStateOf(false) }
        var sort by remember { mutableStateOf("Newest") }
        var sortMenu by remember { mutableStateOf(false) }
        var newDialog by remember { mutableStateOf(false) }
        var fullPlayer by remember { mutableStateOf(false) }
        var statsOpen by remember { mutableStateOf(false) }
        val stats = remember { library.loadStats() }

        var controller by remember { mutableStateOf<MediaController?>(null) }
        var playing by remember { mutableStateOf(false) }
        var shuffle by remember { mutableStateOf(false) }
        var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
        var currentId by remember { mutableStateOf<String?>(null) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }

        DisposableEffect(Unit) {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            val future = MediaController.Builder(ctx, token).buildAsync()
            future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(ctx))
            onDispose { MediaController.releaseFuture(future); controller = null }
        }

        DisposableEffect(controller) {
            val c = controller
            if (c == null) return@DisposableEffect onDispose {}
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (!isPlaying) library.saveStats(stats) }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    currentId = item?.mediaId
                    item?.mediaId?.let { id ->
                        stats.getOrPut(id) { longArrayOf(0L, 0L) }[0]++
                        library.saveStats(stats)
                    }
                }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
                override fun onRepeatModeChanged(mode: Int) { repeat = mode }
            }
            c.addListener(listener)
            playing = c.isPlaying
            shuffle = c.shuffleModeEnabled
            repeat = c.repeatMode
            currentId = c.currentMediaItem?.mediaId
            onDispose { c.removeListener(listener) }
        }

        LaunchedEffect(controller) {
            var tick = 0
            while (true) {
                controller?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0L) }
                if (playing) {
                    currentId?.let { id -> stats.getOrPut(id) { longArrayOf(0L, 0L) }[1] += 500L }
                    tick++
                    if (tick % 20 == 0) library.saveStats(stats)
                }
                delay(500)
            }
        }

        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { u ->
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (songs.none { it.uri == u.toString() }) songs.add(library.readSong(u))
            }
            library.save(songs)
        }

        // Songs shown on screen: tab -> search -> sort
        val shown = songs
            .filter {
                when (tab) {
                    "All" -> true
                    "Favorites" -> it.fav
                    else -> playlists.firstOrNull { p -> p.name == tab }?.uris?.contains(it.uri) == true
                }
            }
            .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
            .let {
                when (sort) {
                    "Title" -> it.sortedBy { s -> s.title.lowercase() }
                    "Artist" -> it.sortedBy { s -> s.artist.lowercase() }
                    else -> it.sortedByDescending { s -> s.added }
                }
            }

        fun play(index: Int) {
            val c = controller ?: return
            c.setMediaItems(shown.map { s ->
                MediaItem.Builder()
                    .setMediaId(s.uri)
                    .setUri(s.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
                            .apply { s.art?.let { setArtworkUri(Uri.fromFile(File(it))) } }
                            .build()
                    )
                    .build()
            }, index, 0L)
            c.prepare()
            c.play()
        }

        fun toggleFav(s: Song) {
            val i = songs.indexOfFirst { it.uri == s.uri }
            if (i >= 0) songs[i] = s.copy(fav = !s.fav)
            library.save(songs)
        }

        fun addToPlaylist(s: Song, name: String) {
            val i = playlists.indexOfFirst { it.name == name }
            if (i >= 0 && s.uri !in playlists[i].uris) playlists[i] = Playlist(name, playlists[i].uris + s.uri)
            library.savePlaylists(playlists)
        }

        fun removeFromPlaylist(s: Song, name: String) {
            val i = playlists.indexOfFirst { it.name == name }
            if (i >= 0) playlists[i] = Playlist(name, playlists[i].uris - s.uri)
            library.savePlaylists(playlists)
        }

        fun deletePlaylist(name: String) {
            playlists.removeAll { it.name == name }
            library.savePlaylists(playlists)
            tab = "All"
        }

        fun remove(s: Song) {
            controller?.let { c ->
                val i = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == s.uri }
                if (i != null) c.removeMediaItem(i)
            }
            songs.removeAll { it.uri == s.uri }
            for (i in playlists.indices) playlists[i] = playlists[i].copy(uris = playlists[i].uris - s.uri)
            library.save(songs)
            library.savePlaylists(playlists)
        }

        val current = songs.firstOrNull { it.uri == currentId }

        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                TopAppBar(
                    title = { Text("Octave", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                    actions = {
                        IconButton(onClick = { statsOpen = true }) {
                            Icon(Icons.Rounded.BarChart, contentDescription = "Statistics")
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf("Newest", "Title", "Artist").forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text(if (o == sort) "• $o" else o) },
                                        onClick = { sort = o; sortMenu = false }
                                    )
                                }
                            }
                        }
                        if (playlists.any { it.name == tab }) {
                            IconButton(onClick = { deletePlaylist(tab) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete playlist")
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { picker.launch(arrayOf("audio/*")) },
                    containerColor = Ink, contentColor = Paper
                ) { Icon(Icons.Rounded.Add, contentDescription = "Add songs") }
            },
            bottomBar = {
                if (current != null) PlayerBar(current, playing, pos, dur, shuffle, repeat, controller) { fullPlayer = true }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (searching) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search songs") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf("All", "Favorites") + playlists.map { it.name }) { n ->
                        FilterChip(selected = tab == n, onClick = { tab = n }, label = { Text(n) })
                    }
                    item {
                        AssistChip(onClick = { newDialog = true }, label = { Text("+ New playlist") })
                    }
                }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                songs.isEmpty() -> "No songs yet. Tap + to add tracks from your phone."
                                query.isNotBlank() -> "Nothing found."
                                tab == "Favorites" -> "No favorites yet. Tap the heart on a song."
                                else -> "Nothing here yet. Use ⋮ on a song to add it to this playlist."
                            },
                            color = Ink.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.uri }) { s ->
                            val active = s.uri == currentId
                            var menu by remember { mutableStateOf(false) }
                            ListItem(
                                modifier = Modifier.clickable { play(shown.indexOf(s)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Art(s.art, 48.dp) },
                                headlineContent = {
                                    Text(
                                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (active) Cobalt else Ink,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { toggleFav(s) }) {
                                            Icon(
                                                if (s.fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = "Favorite",
                                                tint = if (s.fav) Cobalt else Ink.copy(alpha = 0.5f)
                                            )
                                        }
                                        Box {
                                            IconButton(onClick = { menu = true }) {
                                                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                            }
                                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                                playlists.forEach { p ->
                                                    DropdownMenuItem(
                                                        text = { Text("Add to ${p.name}") },
                                                        onClick = { addToPlaylist(s, p.name); menu = false }
                                                    )
                                                }
                                                if (playlists.any { it.name == tab }) {
                                                    DropdownMenuItem(
                                                        text = { Text("Remove from $tab") },
                                                        onClick = { removeFromPlaylist(s, tab); menu = false }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("Remove from Octave") },
                                                    onClick = { remove(s); menu = false }
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        if (statsOpen) {
            BackHandler { statsOpen = false }
            StatsScreen(songs, stats) { statsOpen = false }
        }
        if (fullPlayer && current != null) {
            BackHandler { fullPlayer = false }
            FullPlayer(current, playing, pos, dur, shuffle, repeat, controller, { toggleFav(current) }) {
                fullPlayer = false
            }
        }
        }

        if (newDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { newDialog = false },
                title = { Text("New playlist") },
                text = {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        placeholder = { Text("Name") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = name.trim()
                        if (n.isNotEmpty() && n != "All" && n != "Favorites" && playlists.none { it.name == n }) {
                            playlists.add(Playlist(n, emptyList()))
                            library.savePlaylists(playlists)
                            tab = n
                        }
                        newDialog = false
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun Art(path: String?, size: Dp, onDark: Boolean = false, sample: Int = 4) {
    val bmp = remember(path, sample) {
        path?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?.asImageBitmap()
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp))
            .background(if (onDark) Color.White.copy(alpha = 0.2f) else Ink.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Rounded.MusicNote, contentDescription = null,
                tint = if (onDark) Color.White else Ink.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun PlayerBar(
    song: Song, playing: Boolean, pos: Long, dur: Long,
    shuffle: Boolean, repeat: Int, c: MediaController?, onOpen: () -> Unit
) {
    Surface(
        color = BarColor, contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Art(song.art, 56.dp, onDark = true)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                }
            }
            Slider(
                value = pos.toFloat().coerceAtMost(maxOf(dur, 1L).toFloat()),
                onValueChange = { c?.seekTo(it.toLong()) },
                valueRange = 0f..maxOf(dur, 1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), style = MaterialTheme.typography.bodySmall)
                Text(fmt(dur), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c?.shuffleModeEnabled = !shuffle }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle",
                        tint = Color.White.copy(alpha = if (shuffle) 1f else 0.5f))
                }
                IconButton(onClick = { c?.seekToPrevious() }) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = { if (playing) c?.pause() else c?.play() }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { c?.seekToNext() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = {
                    c?.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = Color.White.copy(alpha = if (repeat == Player.REPEAT_MODE_OFF) 0.5f else 1f)
                    )
                }
            }
        }
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
private fun FullPlayer(
    song: Song, playing: Boolean, pos: Long, dur: Long,
    shuffle: Boolean, repeat: Int, c: MediaController?,
    onFav: () -> Unit, onClose: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Paper, contentColor = Ink) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFav) {
                    Icon(
                        if (song.fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (song.fav) Cobalt else Ink.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Art(song.art, 280.dp, sample = 2)
            Spacer(Modifier.height(32.dp))
            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, color = Ink.copy(alpha = 0.6f))
            Spacer(Modifier.height(16.dp))
            Slider(
                value = pos.toFloat().coerceAtMost(maxOf(dur, 1L).toFloat()),
                onValueChange = { c?.seekTo(it.toLong()) },
                valueRange = 0f..maxOf(dur, 1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), style = MaterialTheme.typography.bodySmall)
                Text(fmt(dur), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c?.shuffleModeEnabled = !shuffle }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle",
                        tint = Ink.copy(alpha = if (shuffle) 1f else 0.4f))
                }
                IconButton(onClick = { c?.seekToPrevious() }) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(onClick = { if (playing) c?.pause() else c?.play() }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { c?.seekToNext() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = {
                    c?.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = Ink.copy(alpha = if (repeat == Player.REPEAT_MODE_OFF) 0.4f else 1f)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatsScreen(songs: List<Song>, stats: Map<String, LongArray>, onClose: () -> Unit) {
    val rows = songs
        .mapNotNull { s -> stats[s.uri]?.let { Triple(s, it[0], it[1]) } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Triple<Song, Long, Long>> { it.second }.thenByDescending { it.third })
    val totalMs = stats.values.sumOf { it[1] }
    val totalPlays = stats.values.sumOf { it[0] }

    Surface(Modifier.fillMaxSize(), color = Paper, contentColor = Ink) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                Text("Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column {
                    Text(fmtTime(totalMs), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("listened", color = Ink.copy(alpha = 0.6f))
                }
                Column {
                    Text("$totalPlays", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("plays", color = Ink.copy(alpha = 0.6f))
                }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Play some songs and your most played tracks will show up here.", color = Ink.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.first.uri }) { r ->
                        val (s, plays, ms) = r
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Art(s.art, 48.dp) },
                            headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("$plays plays, ${fmtTime(ms)}") }
                        )
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
