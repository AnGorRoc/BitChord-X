package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Nightlife
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.music.bitchord.data.DailyArtworkCache
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.music.bitchord.ui.components.PrefetchVerticalArtworkAhead
import com.music.bitchord.ui.components.prefetchArtwork
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton
import java.util.Calendar
import java.util.Locale

data class DailyTopicCard(
    val title: String,
    val searchQuery: String,
    val color: Color,
    val artworkUrl: String? = null,
)

data class DailyTheme(
    val title: String,
    val subtitle: String,
    val topics: List<DailyTopicCard>,
)

private fun fastArtistGenres(artistName: String): List<String> {
    val lower = artistName.lowercase().trim()
    val genres = mutableListOf<String>()
    when {
        // Latin Urban / Trap / Reggaeton
        lower.contains("duki") || lower.contains("bad bunny") || lower.contains("bizarrap") ||
            lower.contains("quevedo") || lower.contains("feid") || lower.contains("mora") ||
            lower.contains("anuel") || lower.contains("rauw") || lower.contains("trueno") ||
            lower.contains("milo j") || lower.contains("ysy a") || lower.contains("tiago pzk") ||
            lower.contains("emilia") || lower.contains("maria becerra") || lower.contains("tini") ||
            lower.contains("nicki nicole") || lower.contains("lit killah") || lower.contains("cro") ||
            lower.contains("khea") || lower.contains("j balvin") || lower.contains("daddy yankee") ||
            lower.contains("ozuna") || lower.contains("maluma") || lower.contains("karol g") ||
            lower.contains("rosalía") || lower.contains("rosalia") || lower.contains("eladio") ||
            lower.contains("myke towers") || lower.contains("sech") || lower.contains("cazzu") -> {
            genres.addAll(listOf("urbano", "trap latino", "reggaeton", "trap", "hip-hop", "pop latino"))
        }

        // Rock Argentino / Rock en español
        lower.contains("soda stereo") || lower.contains("cerati") || lower.contains("charly garcia") ||
            lower.contains("charly garcía") || lower.contains("fito paez") || lower.contains("fito páez") ||
            lower.contains("spinetta") || lower.contains("redondos") || lower.contains("patricio rey") ||
            lower.contains("indio solari") || lower.contains("ciro") || lower.contains("pastillas del abuelo") ||
            lower.contains("la renga") || lower.contains("enanitos verdes") || lower.contains("calamaro") ||
            lower.contains("babasónicos") || lower.contains("babasonicos") || lower.contains("divididos") ||
            lower.contains("bersuit") || lower.contains("las pelotas") || lower.contains("callejeros") ||
            lower.contains("guasones") || lower.contains("intoxicados") || lower.contains("los piojos") ||
            lower.contains("ataque 77") || lower.contains("airbag") || lower.contains("conociendo rusia") ||
            lower.contains("bandalos chinos") || lower.contains("cuarteto de nos") || lower.contains("zoe") ||
            lower.contains("café tacvba") || lower.contains("cafe tacvba") || lower.contains("molotov") ||
            lower.contains("maná") || lower.contains("mana") || lower.contains("bunbury") ||
            lower.contains("héroes del silencio") || lower.contains("heroes del silencio") -> {
            genres.addAll(listOf("rock", "rock argentino", "rock en español", "indie y alternativa"))
        }

        // International Rock & Metal
        lower.contains("queen") || lower.contains("ac/dc") || lower.contains("guns n' roses") ||
            lower.contains("metallica") || lower.contains("iron maiden") || lower.contains("nirvana") ||
            lower.contains("linkin park") || lower.contains("green day") || lower.contains("red hot chili peppers") ||
            lower.contains("arctic monkeys") || lower.contains("the strokes") || lower.contains("radiohead") ||
            lower.contains("coldplay") || lower.contains("the killers") || lower.contains("muse") ||
            lower.contains("pink floyd") || lower.contains("led zeppelin") || lower.contains("beatles") ||
            lower.contains("rolling stones") || lower.contains("foo fighters") || lower.contains("system of a down") ||
            lower.contains("slipknot") || lower.contains("rammstein") || lower.contains("gorillaz") ||
            lower.contains("oasis") || lower.contains("blur") -> {
            genres.addAll(listOf("rock", "metal", "indie y alternativa"))
        }

        // Global Pop
        lower.contains("taylor swift") || lower.contains("dua lipa") || lower.contains("ariana grande") ||
            lower.contains("billie eilish") || lower.contains("harry styles") || lower.contains("bruno mars") ||
            lower.contains("ed sheeran") || lower.contains("justin bieber") || lower.contains("olivia rodrigo") ||
            lower.contains("sabrina carpenter") || lower.contains("lady gaga") || lower.contains("katy perry") ||
            lower.contains("rihanna") || lower.contains("shakira") || lower.contains("shawn mendes") ||
            lower.contains("the weeknd") || lower.contains("chappell roan") || lower.contains("charli xcx") -> {
            genres.addAll(listOf("pop", "pop latino", "dance y electrónica"))
        }

        // Cumbia & Cuarteto
        lower.contains("ke personajes") || lower.contains("damas gratis") || lower.contains("la konga") ||
            lower.contains("ulises bueno") || lower.contains("q' lokura") || lower.contains("luck ra") ||
            lower.contains("bm") || lower.contains("callejero fino") || lower.contains("l-gante") ||
            lower.contains("los palmeras") || lower.contains("rigo tovar") || lower.contains("ángeles azules") ||
            lower.contains("angeles azules") || lower.contains("rodrigo") || lower.contains("walter olmos") -> {
            genres.addAll(listOf("cumbia", "tropical", "fiesta", "urbano"))
        }

        // Electronic & Dance
        lower.contains("daft punk") || lower.contains("avicii") || lower.contains("david guetta") ||
            lower.contains("calvin harris") || lower.contains("martin garrix") || lower.contains("tiësto") ||
            lower.contains("tiesto") || lower.contains("skrillex") || lower.contains("marshmello") ||
            lower.contains("fred again") || lower.contains("peggy gou") || lower.contains("fisher") ||
            lower.contains("rufus du sol") || lower.contains("swedish house mafia") || lower.contains("kygo") ||
            lower.contains("deadmau5") || lower.contains("alok") || lower.contains("boris brejcha") -> {
            genres.addAll(listOf("dance y electrónica", "electrónica", "fiesta", "energía"))
        }

        // Hip-Hop / Rap
        lower.contains("eminem") || lower.contains("drake") || lower.contains("kanye") ||
            lower.contains("kendrick lamar") || lower.contains("travis scott") || lower.contains("snoop dogg") ||
            lower.contains("2pac") || lower.contains("notorious") || lower.contains("50 cent") ||
            lower.contains("post malone") || lower.contains("future") || lower.contains("metro boomin") ||
            lower.contains("playboi carti") || lower.contains("lil uzi") || lower.contains("juice wrld") ||
            lower.contains("xxxtentacion") || lower.contains("mac miller") || lower.contains("j. cole") -> {
            genres.addAll(listOf("hip-hop", "hip hop", "urbano", "trap"))
        }
    }
    return genres
}

private fun expandGenreKeywords(genres: Collection<String>): Set<String> {
    val expanded = mutableSetOf<String>()
    for (g in genres) {
        val lower = g.lowercase().trim()
        if (lower.isEmpty()) continue
        expanded.add(lower)
        when {
            lower.contains("trap") || lower.contains("reggaeton") || lower.contains("reguetón") || lower.contains("perreo") || lower.contains("rkt") || lower.contains("urban") -> {
                expanded.addAll(listOf("urbano", "trap latino", "reggaeton", "pop latino", "hip-hop", "hiphop en español"))
            }
            lower.contains("rock") -> {
                expanded.addAll(listOf("rock", "rock argentino", "rock en español", "indie y alternativa"))
            }
            lower.contains("metal") || lower.contains("hardcore") || lower.contains("punk") -> {
                expanded.addAll(listOf("metal", "rock", "indie y alternativa"))
            }
            lower.contains("pop") -> {
                expanded.addAll(listOf("pop", "pop latino"))
            }
            lower.contains("cumbia") || lower.contains("cuarteto") || lower.contains("salsa") || lower.contains("bachata") -> {
                expanded.addAll(listOf("cumbia", "tropical", "fiesta", "urbano"))
            }
            lower.contains("electronic") || lower.contains("techno") || lower.contains("house") || lower.contains("edm") || lower.contains("dance") -> {
                expanded.addAll(listOf("dance y electrónica", "electrónica", "fiesta", "energía"))
            }
            lower.contains("indie") || lower.contains("alternative") || lower.contains("alternativ") -> {
                expanded.addAll(listOf("indie y alternativa", "rock"))
            }
            lower.contains("hip hop") || lower.contains("hip-hop") || lower.contains("rap") -> {
                expanded.addAll(listOf("hip-hop", "hiphop en español", "urbano"))
            }
            lower.contains("chill") || lower.contains("relax") || lower.contains("ambient") || lower.contains("lo fi") || lower.contains("lofi") -> {
                expanded.addAll(listOf("calma", "dormir", "concentración"))
            }
            lower.contains("workout") || lower.contains("gym") || lower.contains("fitness") -> {
                expanded.addAll(listOf("ejercicio", "energía"))
            }
        }
    }
    return expanded
}

private fun isObscureForeignGenre(title: String): Boolean {
    val t = title.lowercase().trim()
    val obscureKeywords = listOf(
        "iraqi", "iraq",
        "árabe", "arabe", "arabic",
        "africana", "african",
        "bollywood", "india", "punjabi", "desi", "hindi",
        "ghazal", "sufi",
        "funk brasileño", "funk brasileno", "brasileña", "brasilena", "brazilian",
        "vallenato",
        "pop japonés", "pop japones", "j-pop", "jpop",
        "polka",
        "familia", "family", "niños", "ninos",
        "cristiana", "gospel", "góspel", "christian",
        "folk y tradicional",
        "musicales y bandas sonoras",
    )
    return obscureKeywords.any { kw -> t == kw || t.contains(kw) }
}

private fun isMainstreamCategory(title: String): Boolean {
    val t = title.lowercase().trim()
    val mainstream = listOf(
        "fiesta", "party",
        "calma", "chill", "relax",
        "concentración", "concentracion", "focus",
        "ejercicio", "entrenamiento", "workout",
        "energía", "energia", "energize",
        "sentirse bien", "feel good",
        "romance", "dormir", "sleep", "gaming",
        "urbano", "reggaeton", "trap",
        "pop", "pop latino",
        "rock", "rock argentino", "rock en español", "rock en espanol",
        "cumbia", "tropical",
        "dance y electrónica", "dance y electronica", "electrónica", "electronica", "electronic", "dance",
        "hip-hop", "hip hop", "hiphop en español", "hiphop en espanol",
        "indie y alternativa", "indie", "alternativa",
        "metal", "r&b y soul", "r&b", "soul",
    )
    return mainstream.any { kw -> t == kw || t.contains(kw) }
}

private val WORD_SPLIT = Regex("[\\s,/&+\\-]+")

private fun matchesAffinity(title: String, userKeywords: Set<String>): Boolean {
    if (userKeywords.isEmpty()) return false
    val t = title.lowercase().trim()
    if (userKeywords.contains(t)) return true

    val titleWords = t.split(WORD_SPLIT).filter { it.length >= 3 }
    for (kw in userKeywords) {
        if (kw.length < 3) continue
        if (t == kw || t.contains(kw) || kw.contains(t)) return true

        val kwWords = kw.split(WORD_SPLIT).filter { it.length >= 3 }
        if (titleWords.any { tw -> kwWords.contains(tw) }) return true
    }
    return false
}

private fun getDailyTheme(): DailyTheme {
    val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    return when (dayOfWeek) {
        Calendar.MONDAY -> DailyTheme(
            title = "Motivación y Energía",
            subtitle = "Arranca la semana con máxima potencia",
            topics = listOf(
                DailyTopicCard("Workout Hits", "workout motivation gym hits", Color(0xFFE11D48)),
                DailyTopicCard("Running Pace", "running high energy bpm workout", Color(0xFF007A5A)),
                DailyTopicCard("Beast Mode", "gym trap phonk workout", Color(0xFF2563EB)),
                DailyTopicCard("Power Anthems", "pop power anthems workout energy", Color(0xFF7928CA)),
            ),
        )
        Calendar.TUESDAY -> DailyTheme(
            title = "Cápsulas del Tiempo",
            subtitle = "Un viaje a través de los sonidos de cada época",
            topics = listOf(
                DailyTopicCard("80s Synthwave", "best of 80s synthpop disco hits", Color(0xFFD81B60)),
                DailyTopicCard("90s Golden Era", "best 90s rock grunge pop", Color(0xFFD97706)),
                DailyTopicCard("2000s Nostalgia", "2000s pop punk rb hits", Color(0xFF0284C7)),
                DailyTopicCard("2010s Club Era", "2010s edm pop hits festival", Color(0xFF059669)),
            ),
        )
        Calendar.WEDNESDAY -> DailyTheme(
            title = "Enfoque y Calma",
            subtitle = "Concentración y serenidad a mitad de semana",
            topics = listOf(
                DailyTopicCard("Lo-Fi Beats", "lofi hip hop study beats chill", Color(0xFF6366F1)),
                DailyTopicCard("Acoustic Coffee", "acoustic coffee chill morning", Color(0xFF0D9488)),
                DailyTopicCard("Deep Focus", "ambient focus electronic study", Color(0xFF1E3A8A)),
                DailyTopicCard("Modern Piano", "peaceful modern solo piano", Color(0xFF475569)),
            ),
        )
        Calendar.THURSDAY -> DailyTheme(
            title = "Frecuencia Urbana",
            subtitle = "Ritmo callejero, barras y flow",
            topics = listOf(
                DailyTopicCard("Trap Latino", "trap latino urbano hits", Color(0xFFDC2626)),
                DailyTopicCard("Reggaeton Duro", "reggaeton nuevo perreo bailable", Color(0xFFF59E0B)),
                DailyTopicCard("Hip-Hop Clásico", "golden era 90s hip hop boom bap", Color(0xFF007A5A)),
                DailyTopicCard("Afrobeats", "afrobeats amapiano groove", Color(0xFF7C2D12)),
            ),
        )
        Calendar.FRIDAY -> DailyTheme(
            title = "Modo Finde y Fiesta",
            subtitle = "Los mejores ritmos para empezar la fiesta",
            topics = listOf(
                DailyTopicCard("EDM & Club", "electronic dance music edm festival", Color(0xFF9333EA)),
                DailyTopicCard("Previa Bailable", "cumbia rkt previa fiesta", Color(0xFFE11D48)),
                DailyTopicCard("Tech House", "tech house underground groove club", Color(0xFF0284C7)),
                DailyTopicCard("Pop Party", "dance pop hits party weekend", Color(0xFFEA580C)),
            ),
        )
        Calendar.SATURDAY -> DailyTheme(
            title = "Descubrimiento de Sábado",
            subtitle = "Vibras frescas para disfrutar el fin de semana",
            topics = listOf(
                DailyTopicCard("Indie Spectrum", "indie rock bedroom pop alternative", Color(0xFF059669)),
                DailyTopicCard("Global Pop", "top global pop hits chart", Color(0xFFD81B60)),
                DailyTopicCard("Synthwave Neon", "synthwave retrowave neon drive", Color(0xFF4F46E5)),
                DailyTopicCard("Rock Legends", "classic rock anthems legends", Color(0xFF7F1D1D)),
            ),
        )
        else -> DailyTheme(
            title = "Relax y Desconexión",
            subtitle = "Desconecta, descansa y recarga energías",
            topics = listOf(
                DailyTopicCard("Sunday Chill", "chillout lazy sunday acoustic", Color(0xFF047857)),
                DailyTopicCard("Soul & Groove", "neo soul modern rb groove", Color(0xFF92400E)),
                DailyTopicCard("Atmosphere & Sleep", "ambient sleep soundscape meditation", Color(0xFF1E293B)),
                DailyTopicCard("Warm Guitars", "fingerstyle acoustic guitar peaceful", Color(0xFF0369A1)),
            ),
        )
    }
}

data class ChartCountry(
    val code: String,
    val name: String,
    val flag: String,
)

private fun defaultCountryCode(): String {
    val detected = YtMusicRepository.detectedRegion
    if (!detected.isNullOrBlank()) return detected
    return "AR"
}

private fun regionCountryName(code: String?): String {
    val effectiveCode = if (code.isNullOrBlank() || code.equals("AUTO", ignoreCase = true)) {
        defaultCountryCode()
    } else {
        code
    }
    if (effectiveCode.equals("GLOBAL", ignoreCase = true)) {
        return "Global"
    }
    val loc = Locale("", effectiveCode)
    return loc.getDisplayCountry(Locale.getDefault()).ifBlank { effectiveCode }
}

private fun regionDisplayName(code: String?): String {
    val effectiveCode = if (code.isNullOrBlank() || code.equals("AUTO", ignoreCase = true)) {
        defaultCountryCode()
    } else {
        code
    }
    if (effectiveCode.equals("GLOBAL", ignoreCase = true)) {
        return "Global"
    }
    val loc = Locale("", effectiveCode)
    return loc.getDisplayCountry(Locale.getDefault()).ifBlank { effectiveCode }
}

private val REAL_CHART_COUNTRIES by lazy {
    val supportedCodes = listOf(
        "AR", "BO", "BR", "CA", "CL", "CO", "CR", "CZ", "DK", "DO",
        "EC", "EG", "ES", "FI", "FR", "GB", "DE", "GT", "HN", "HU",
        "ID", "IE", "IL", "IN", "IT", "JP", "KE", "MX", "NL", "NI",
        "NG", "NO", "NZ", "PA", "PE", "PH", "PL", "PT", "RO", "RU",
        "SA", "SE", "TR", "UA", "AE", "US", "UY", "VE", "ZA", "KR"
    )
    val list = mutableListOf(
        ChartCountry("AUTO", "Automático (IP)", "IP"),
        ChartCountry("GLOBAL", "Global", "GL"),
    )
    val currentLocale = Locale.getDefault()
    val detected = YtMusicRepository.detectedRegion
    val allCodes = if (detected != null && detected !in supportedCodes) {
        listOf(detected) + supportedCodes
    } else {
        supportedCodes
    }
    val countries = allCodes.map { code ->
        val loc = Locale("", code)
        val name = loc.getDisplayCountry(currentLocale).ifBlank { loc.displayCountry }
        ChartCountry(code, name, code)
    }.sortedBy { it.name }
    list.addAll(countries)
    list
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    chartsState: UiState<List<HomeShelf>> = UiState.Loading,
    newReleasesState: UiState<List<HomeShelf>> = UiState.Loading,
    chartsRegion: String? = null,
    onRegionChange: (String?) -> Unit = {},
    onRefreshCharts: () -> Unit = {},
    onSongClick: ((ShelfItem) -> Unit)? = null,
    onSongOptionsClick: ((ShelfItem) -> Unit)? = null,
    onItemClick: ((ShelfItem) -> Unit)? = null,
    onPlayAll: ((List<ShelfItem>) -> Unit)? = null,
    onShufflePlay: ((List<ShelfItem>) -> Unit)? = null,
    onAddToQueue: ((List<ShelfItem>) -> Unit)? = null,
    onOpenTrendingPlaylist: ((regionCode: String?, regionName: String, allSongs: List<ShelfItem>, videoShelf: HomeShelf?, artistShelf: HomeShelf?) -> Unit)? = null,
    onOpenTopicPlaylist: ((topicTitle: String, searchQuery: String, coverUrl: String?) -> Unit)? = null,
) {
    var showChartOptionsModal by remember { mutableStateOf(false) }

    val chartShelves = (chartsState as? UiState.Success)?.data.orEmpty()
    val topSongsShelf = remember(chartShelves) {
        chartShelves.firstOrNull { shelf ->
            shelf.items.any { it.videoId != null }
        }
    }
    val allTopSongs = remember(topSongsShelf, chartShelves) {
        val fromShelf = topSongsShelf?.items?.filter { it.videoId != null }
        if (!fromShelf.isNullOrEmpty()) {
            fromShelf
        } else {
            chartShelves.flatMap { it.items }.filter { it.videoId != null }.distinctBy { it.videoId }
        }
    }
    val previewTopSongs = remember(allTopSongs) { allTopSongs.take(6) }
    val topVideosShelf = remember(chartShelves, topSongsShelf) {
        chartShelves.firstOrNull { it !== topSongsShelf && (it.items.any { item -> item.isVideo } || it.title.contains("video", ignoreCase = true)) }
    }
    val topArtistsShelf = remember(chartShelves, topSongsShelf, topVideosShelf) {
        chartShelves.firstOrNull { it !== topSongsShelf && it !== topVideosShelf && it.items.any { item -> item.videoId == null } }
    }

    val newReleasesShelves = (newReleasesState as? UiState.Success)?.data.orEmpty()
    val freshReleases = remember(newReleasesShelves) {
        val allItems = newReleasesShelves.flatMap { it.items }.distinctBy { it.browseId ?: it.videoId ?: it.title }
        if (allItems.isNotEmpty()) allItems else newReleasesShelves.firstOrNull()?.items.orEmpty()
    }

    // Modal Sheet de Opciones de Tendencias y Selección de País
    if (showChartOptionsModal) {
        ModalBottomSheet(
            onDismissRequest = { showChartOptionsModal = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            ChartOptionsModalContent(
                currentRegion = chartsRegion,
                hasSongs = allTopSongs.isNotEmpty(),
                onSelectRegion = { code ->
                    onRegionChange(code)
                    showChartOptionsModal = false
                },
                onPlayAll = {
                    showChartOptionsModal = false
                    onPlayAll?.invoke(allTopSongs)
                },
                onShuffle = {
                    showChartOptionsModal = false
                    onShufflePlay?.invoke(allTopSongs)
                },
                onAddToQueue = {
                    showChartOptionsModal = false
                    onAddToQueue?.invoke(allTopSongs)
                },
                onRefresh = {
                    showChartOptionsModal = false
                    onRefreshCharts()
                },
            )
        }
    }

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            val successSections = (state as? UiState.Success)?.data
            val userAffinityKeywords by produceState<Set<String>>(initialValue = emptySet()) {
                withContext(Dispatchers.IO) {
                    val rawKeywords = mutableSetOf<String>()
                    // 1. Estadísticas de reproducción locales en BitChord
                    runCatching {
                        val statsSummary = ListeningStats.summary(ReplayPeriod.ALL_TIME)
                        statsSummary.genres.take(20).forEach { genre ->
                            val name = genre.title.lowercase().trim()
                            if (name.isNotEmpty()) rawKeywords.add(name)
                        }
                        statsSummary.artists.take(40).forEach { artist ->
                            val artistName = artist.title.lowercase().trim()
                            if (artistName.isNotEmpty()) {
                                rawKeywords.add(artistName)
                                ArtistFacts.genresFor(artist.title).forEach { g ->
                                    val clean = g.lowercase().trim()
                                    if (clean.isNotEmpty()) rawKeywords.add(clean)
                                }
                                rawKeywords.addAll(fastArtistGenres(artist.title))
                            }
                        }
                        statsSummary.songs.take(40).forEach { song ->
                            val artistName = song.song.artist.lowercase().trim()
                            if (artistName.isNotEmpty()) {
                                rawKeywords.add(artistName)
                                ArtistFacts.genresFor(song.song.artist).forEach { g ->
                                    val clean = g.lowercase().trim()
                                    if (clean.isNotEmpty()) rawKeywords.add(clean)
                                }
                                rawKeywords.addAll(fastArtistGenres(song.song.artist))
                            }
                        }
                    }

                    // 2. Historial reciente
                    runCatching {
                        val history = YtMusicRepository.recentHistory().getOrNull().orEmpty()
                        history.take(40).forEach { song ->
                            val artist = song.artist.lowercase().trim()
                            if (artist.isNotEmpty()) {
                                rawKeywords.add(artist)
                                rawKeywords.addAll(fastArtistGenres(song.artist))
                            }
                            song.artists.forEach { a ->
                                val aName = a.name.lowercase().trim()
                                if (aName.isNotEmpty()) {
                                    rawKeywords.add(aName)
                                    ArtistFacts.genresFor(a.name).forEach { g ->
                                        val clean = g.lowercase().trim()
                                        if (clean.isNotEmpty()) rawKeywords.add(clean)
                                    }
                                    rawKeywords.addAll(fastArtistGenres(a.name))
                                }
                            }
                            ArtistFacts.genresFor(song.artist).forEach { g ->
                                val clean = g.lowercase().trim()
                                if (clean.isNotEmpty()) rawKeywords.add(clean)
                            }
                        }
                    }

                    value = expandGenreKeywords(rawKeywords)
                }
            }

            val allMoods = remember(successSections, userAffinityKeywords) {
                val rawList = successSections?.flatMap(MoodGenreSection::items)
                    ?.distinctBy { it.browseId to it.params }
                    .orEmpty()
                if (rawList.isEmpty()) {
                    emptyList()
                } else {
                    val firstSectionItems = successSections?.firstOrNull()?.items
                        ?.map { it.browseId to it.params }?.toSet().orEmpty()
                    val isFirstSectionPersonalized = successSections?.firstOrNull()?.let { section ->
                        val laterItems = successSections.drop(1).flatMap { it.items }.map { it.browseId to it.params }.toSet()
                        section.title.equals("For you", ignoreCase = true) ||
                            section.title.equals("Para ti", ignoreCase = true) ||
                            section.items.any { (it.browseId to it.params) in laterItems }
                    } ?: false

                    fun affinityScore(item: MoodGenre, index: Int): Double {
                        val titleLower = item.title.lowercase().trim()
                        val matchesUserTaste = matchesAffinity(titleLower, userAffinityKeywords)
                        val isFromPersonalized = isFirstSectionPersonalized && (item.browseId to item.params) in firstSectionItems
                        val isNicheForeign = isObscureForeignGenre(titleLower)
                        val isMainstream = isMainstreamCategory(titleLower)

                        return when {
                            // 1. Gusto explícito del usuario y respaldado por YouTube
                            matchesUserTaste && isFromPersonalized -> 5000.0 - index
                            // 2. Coincide directamente con los gustos y artistas del usuario
                            matchesUserTaste -> 4000.0 - index
                            // 3. Recomendación de YouTube que NO coincide con el usuario y es un género foráneo de nicho: penalizar
                            isFromPersonalized && isNicheForeign -> -1000.0 - index
                            // 4. Recomendación de YouTube que no es foránea
                            isFromPersonalized -> 100.0 - index
                            // 5. Categoría mainstream o mood universal (cuando no hay afinidad previa)
                            isMainstream -> 500.0 - index
                            // 6. Géneros foráneos de nicho sin afinidad de usuario: al final de la lista
                            isNicheForeign -> -2000.0 - index
                            // 7. Base neutra preservando orden original
                            else -> 0.0 - index
                        }
                    }

                    // Score once per item (the index comes from withIndex, not indexOf, which is O(n)
                    // per call and made this sort quadratic while it blocked composition).
                    rawList.withIndex()
                        .map { (index, item) -> item to affinityScore(item, index) }
                        .sortedByDescending { it.second }
                        .map { it.first }
                }
            }
            val bentoRows = remember(allMoods) { buildBentoRows(allMoods) }

            val moodPairs = remember(allMoods) { allMoods.chunked(2) }
            val dailyTheme = remember { getDailyTheme() }
            val dailyTopicArtworks by produceState<Map<String, String>>(
                initialValue = remember(dailyTheme) {
                    dailyTheme.topics.mapNotNull { t -> DailyArtworkCache.get(t.searchQuery)?.let { t.searchQuery to it } }.toMap()
                },
                key1 = dailyTheme,
            ) {
                withContext(Dispatchers.IO) {
                    val resolved = mutableMapOf<String, String>()
                    dailyTheme.topics.forEach { topic ->
                        val cached = DailyArtworkCache.get(topic.searchQuery)
                        if (cached != null) {
                            resolved[topic.searchQuery] = cached
                        } else {
                            DailyArtworkCache.getOrFetch(topic.searchQuery)?.let { thumb ->
                                resolved[topic.searchQuery] = thumb
                            }
                        }
                    }
                    value = resolved
                }
            }

            if (successSections != null) {
                val rowArtwork = remember(allMoods, columns) {
                    allMoods.chunked(columns).map { row -> row.mapNotNull { it.thumbnailUrl } }
                }
                PrefetchVerticalArtworkAhead(
                    state = listState,
                    artworkByItem = rowArtwork,
                    ahead = 4,
                )
                val context = LocalPlatformContext.current
                LaunchedEffect(rowArtwork) {
                    if (rowArtwork.isNotEmpty()) {
                        prefetchArtwork(context, rowArtwork.take(4).flatten())
                    }
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                }

                // ---- SECCIÓN 1: TENDENCIAS DEL MOMENTO ----
                item(key = "charts-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Whatshot,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5722),
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.trending_now),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            Text(
                                text = stringResource(Res.string.trending_subtitle, regionCountryName(chartsRegion)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(onClick = { showChartOptionsModal = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(Res.string.trending_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                when (chartsState) {
                    UiState.Loading -> item(key = "charts-loading") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(5) {
                                ShimmerBox(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    RoundedCornerShape(12.dp),
                                )
                            }
                        }
                    }
                    is UiState.Error -> item(key = "charts-error") {
                        MessageState(
                            message = chartsState.message,
                            actionLabel = stringResource(Res.string.retry),
                            onAction = onRefreshCharts,
                        )
                    }
                    is UiState.Success -> {
                        if (allTopSongs.isEmpty()) {
                            item(key = "charts-empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = PAGE_GUTTER, vertical = 20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.nothing_to_explore),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            val hasMore = allTopSongs.size > 5
                            item(key = "charts-preview-container") {
                                Box(
                                    modifier = if (hasMore) {
                                        Modifier
                                            .fillMaxWidth()
                                            .height(365.5.dp)
                                            .clip(RectangleShape)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .wrapContentHeight(align = Alignment.Top, unbounded = true),
                                    ) {
                                        previewTopSongs.forEachIndexed { index, item ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
                                            ) {
                                                TopTrendingRow(
                                                    rank = index + 1,
                                                    item = item,
                                                    onClick = { onSongClick?.invoke(item) },
                                                    onOptionsClick = { onSongOptionsClick?.invoke(item) },
                                                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                                                )
                                                if (index < previewTopSongs.lastIndex) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(start = 68.dp, end = PAGE_GUTTER),
                                                        thickness = 0.5.dp,
                                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (hasMore) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .align(Alignment.BottomCenter)
                                                .background(
                                                    Brush.verticalGradient(
                                                        0.0f to Color.Transparent,
                                                        0.32f to Color.Transparent,
                                                        0.70f to MaterialTheme.colorScheme.background.copy(alpha = 0.70f),
                                                        1.0f to MaterialTheme.colorScheme.background,
                                                    ),
                                                ),
                                        )
                                    }
                                }
                            }

                            // Botón simple indicando que hay más abajo
                            if (hasMore) {
                                item(key = "view-more-trending") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                val effectiveCode = chartsRegion ?: defaultCountryCode()
                                                onOpenTrendingPlaylist?.invoke(
                                                    chartsRegion,
                                                    regionCountryName(effectiveCode),
                                                    allTopSongs,
                                                    topVideosShelf,
                                                    topArtistsShelf,
                                                )
                                            }
                                            .padding(vertical = 10.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.show_all),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- SECCIÓN 2: NUEVOS LANZAMIENTOS (NEW RELEASES) ----
                if (freshReleases.isNotEmpty()) {
                    item(key = "new-releases-header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = PAGE_GUTTER,
                                    end = PAGE_GUTTER,
                                    top = 20.dp,
                                    bottom = 8.dp,
                                ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NewReleases,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.new_releases_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            Text(
                                text = stringResource(Res.string.new_releases_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item(key = "new-releases-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(freshReleases.take(12), key = { it.browseId ?: it.title }) { item ->
                                NewReleaseCard(
                                    item = item,
                                    onClick = { onItemClick?.invoke(item) },
                                )
                            }
                        }
                    }
                }

                // ---- SECCIÓN 3: SELECCIÓN DEL DÍA DINÁMICA (CARRUSEL HORIZONTAL GLASS) ----
                item(key = "daily-theme-header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 22.dp,
                                bottom = 12.dp,
                            ),
                    ) {
                        Text(
                            text = dailyTheme.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = dailyTheme.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "daily-theme-carousel") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(dailyTheme.topics, key = { it.title }) { topic ->
                            val effectiveArt = dailyTopicArtworks[topic.searchQuery] ?: DailyArtworkCache.get(topic.searchQuery)
                            val resolvedTopic = remember(topic, effectiveArt) { topic.copy(artworkUrl = effectiveArt) }
                            DailyTopicGlassCard(
                                topic = resolvedTopic,
                                onClick = {
                                    onOpenTopicPlaylist?.invoke(
                                        resolvedTopic.title,
                                        resolvedTopic.searchQuery,
                                        resolvedTopic.artworkUrl,
                                    )
                                },
                            )
                        }
                    }
                }

                // ---- SECCIÓN 4: EXPLORAR TODO (BENTO GLASS ASIMÉTRICO CON ILUMINACIÓN VOLUMÉTRICA) ----
                item(key = "browse-all-header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 26.dp,
                                bottom = 12.dp,
                            ),
                    ) {
                        Text(
                            text = stringResource(Res.string.browse_all_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(Res.string.browse_all_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(
                            state.message,
                            actionLabel = stringResource(Res.string.retry),
                            onAction = onRetry,
                        )
                    }
                    is UiState.Success -> {
                        itemsIndexed(
                            bentoRows,
                            key = { index, _ -> "bento_row_$index" },
                        ) { rowIndex, row ->
                            BentoRow(
                                row = row,
                                onCategoryClick = onCategoryClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopTrendingRow(
    rank: Int,
    item: ShelfItem,
    onClick: () -> Unit,
    onOptionsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rankColor = when (rank) {
        1 -> Color(0xFFFFB800)
        2 -> Color(0xFFCBD5E1)
        3 -> Color(0xFFF97316)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .requiredHeight(66.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Numeración sin fondo y más chica
        Text(
            text = "$rank",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = rankColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(26.dp),
        )

        Spacer(Modifier.width(10.dp))

        // Cover
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            item.thumbnailUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Title and artist
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Botón de 3 puntos que abre el modal estándar de track
        IconButton(
            onClick = { onOptionsClick?.invoke() },
            modifier = Modifier.size(36.dp).menuAnchor(),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NewReleaseCard(
    item: ShelfItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            item.thumbnailUrl?.let { art ->
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DailyTopicGlassCard(
    topic: DailyTopicCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(210.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        topic.color.copy(alpha = 0.88f),
                        topic.color.copy(alpha = 0.52f),
                        Color(0xFF0F1218),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(420f, 210f),
                ),
            )
            .border(
                BorderStroke(0.75.dp, Color.White.copy(alpha = 0.16f)),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.62f)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Mix especial",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.70f),
                fontWeight = FontWeight.Medium,
            )
        }

        topic.artworkUrl?.let { url ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 10.dp)
                    .rotate(22f)
                    .size(68.dp)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DecadePillCard(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.10f)),
                ),
            )
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun ChartOptionsModalContent(
    currentRegion: String?,
    hasSongs: Boolean,
    onSelectRegion: (String?) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onRefresh: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) REAL_CHART_COUNTRIES
        else REAL_CHART_COUNTRIES.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.code.contains(searchQuery, ignoreCase = true) ||
                (it.code == "GLOBAL" && (
                    "mundial".contains(searchQuery, ignoreCase = true) ||
                    "mundo".contains(searchQuery, ignoreCase = true) ||
                    "worldwide".contains(searchQuery, ignoreCase = true)
                )) ||
                (it.code == "AUTO" && (
                    "automático".contains(searchQuery, ignoreCase = true) ||
                    "automatic".contains(searchQuery, ignoreCase = true) ||
                    "ip".contains(searchQuery, ignoreCase = true)
                ))
        }
    }
    val effectiveCode = currentRegion ?: defaultCountryCode()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        // Header
        Text(
            text = stringResource(Res.string.trending_options),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (hasSongs) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPlayAll)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.play_all_top),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShuffle)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.shuffle_play),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddToQueue)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.add_to_queue),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRefresh)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.refresh_trending),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )

        // Buscador de País
        Text(
            text = stringResource(Res.string.change_trending_region),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.search_country_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        innerTextField()
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Lista de Países
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
        ) {
            items(filteredCountries, key = { it.code }) { country ->
                val isSelected = when {
                    country.code == "AUTO" -> currentRegion == null || currentRegion.equals("AUTO", ignoreCase = true)
                    country.code == "GLOBAL" -> currentRegion.equals("GLOBAL", ignoreCase = true)
                    else -> effectiveCode.equals(country.code, ignoreCase = true) &&
                        currentRegion != null &&
                        !currentRegion.equals("GLOBAL", ignoreCase = true) &&
                        !currentRegion.equals("AUTO", ignoreCase = true)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val selectedCode = if (country.code == "AUTO") null else country.code
                            onSelectRegion(selectedCode)
                        }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = country.flag,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = country.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed class BentoRowItem {
    data class AsymmetricLeft(
        val wide: MoodGenre,
        val wideColor: Color,
        val narrow: MoodGenre,
        val narrowColor: Color,
    ) : BentoRowItem()

    data class AsymmetricRight(
        val narrow: MoodGenre,
        val narrowColor: Color,
        val wide: MoodGenre,
        val wideColor: Color,
    ) : BentoRowItem()

    data class Balanced(
        val first: MoodGenre,
        val firstColor: Color,
        val second: MoodGenre,
        val secondColor: Color,
    ) : BentoRowItem()

    data class Single(
        val item: MoodGenre,
        val color: Color,
    ) : BentoRowItem()
}

private fun buildBentoRows(items: List<MoodGenre>): List<BentoRowItem> {
    val result = mutableListOf<BentoRowItem>()
    var i = 0
    var rowIndex = 0
    var prevCol0Idx = -1
    var prevCol1Idx = -1

    while (i < items.size) {
        if (i + 1 < items.size) {
            val item1 = items[i]
            val item2 = items[i + 1]

            val (color1, col0Idx) = assignRowColor(
                item = item1,
                rowIndex = rowIndex,
                colIndex = 0,
                conflictIndices = if (prevCol0Idx >= 0) setOf(prevCol0Idx) else emptySet(),
            )
            val (color2, col1Idx) = assignRowColor(
                item = item2,
                rowIndex = rowIndex,
                colIndex = 1,
                conflictIndices = setOfNotNull(col0Idx, if (prevCol1Idx >= 0) prevCol1Idx else null),
            )

            when (rowIndex % 3) {
                0 -> result.add(BentoRowItem.AsymmetricLeft(item1, color1, item2, color2))
                1 -> result.add(BentoRowItem.Balanced(item1, color1, item2, color2))
                2 -> result.add(BentoRowItem.AsymmetricRight(item1, color1, item2, color2))
            }
            prevCol0Idx = col0Idx
            prevCol1Idx = col1Idx
            rowIndex++
            i += 2
        } else {
            val item = items[i]
            val (color, _) = assignRowColor(
                item = item,
                rowIndex = rowIndex,
                colIndex = 0,
                conflictIndices = if (prevCol0Idx >= 0) setOf(prevCol0Idx) else emptySet(),
            )
            result.add(BentoRowItem.Single(item, color))
            i += 1
        }
    }
    return result
}

@Composable
private fun BentoRow(
    row: BentoRowItem,
    onCategoryClick: (MoodGenre) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 10.dp),
    ) {
        when (row) {
            is BentoRowItem.AsymmetricLeft -> {
                BentoGlassCard(
                    item = row.wide,
                    cardColor = row.wideColor,
                    isWide = true,
                    onClick = { onCategoryClick(row.wide) },
                    modifier = Modifier.weight(1.42f),
                )
                BentoGlassCard(
                    item = row.narrow,
                    cardColor = row.narrowColor,
                    isWide = false,
                    onClick = { onCategoryClick(row.narrow) },
                    modifier = Modifier.weight(1f),
                )
            }
            is BentoRowItem.AsymmetricRight -> {
                BentoGlassCard(
                    item = row.narrow,
                    cardColor = row.narrowColor,
                    isWide = false,
                    onClick = { onCategoryClick(row.narrow) },
                    modifier = Modifier.weight(1f),
                )
                BentoGlassCard(
                    item = row.wide,
                    cardColor = row.wideColor,
                    isWide = true,
                    onClick = { onCategoryClick(row.wide) },
                    modifier = Modifier.weight(1.42f),
                )
            }
            is BentoRowItem.Balanced -> {
                BentoGlassCard(
                    item = row.first,
                    cardColor = row.firstColor,
                    isWide = false,
                    onClick = { onCategoryClick(row.first) },
                    modifier = Modifier.weight(1f),
                )
                BentoGlassCard(
                    item = row.second,
                    cardColor = row.secondColor,
                    isWide = false,
                    onClick = { onCategoryClick(row.second) },
                    modifier = Modifier.weight(1f),
                )
            }
            is BentoRowItem.Single -> {
                BentoGlassCard(
                    item = row.item,
                    cardColor = row.color,
                    isWide = true,
                    onClick = { onCategoryClick(row.item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatBentoTitle(rawTitle: String): String {
    val title = rawTitle.trim()
    if (title.contains('\n')) return title

    // Si contiene '&', la bajada de línea se produce justo antes del '&' para que baje el '&'
    if (title.contains('&')) {
        if (title.contains(" & ")) {
            return title.replaceFirst(" & ", "\n& ")
        }
        val andRegex = Regex("""(\S+)\s*&\s*(\S+)""")
        if (andRegex.containsMatchIn(title)) {
            return title.replaceFirst(Regex("""\s*&\s*"""), "\n& ")
        }
    }

    // En todos los que tengan dos palabras siempre hacer bajada de línea
    val words = title.split(Regex("""\s+"""))
    if (words.size == 2) {
        return "${words[0]}\n${words[1]}"
    }

    return title
}

@Composable
fun BentoGlassCard(
    item: MoodGenre,
    cardColor: Color = remember(item.title) { BENTO_PALETTE[(item.title.hashCode() and 0x7FFFFFFF) % BENTO_PALETTE.size] },
    isWide: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        cardColor.copy(alpha = 0.88f),
                        cardColor.copy(alpha = 0.52f),
                        Color(0xFF0F1218),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(360f, 180f),
                ),
            )
            .border(
                BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        // Portada nítida en el sector derecho sin velos opacos que tapen la imagen
        item.thumbnailUrl?.let { artwork ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (isWide) 0.44f else 0.46f)
                    .align(Alignment.CenterEnd),
            ) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Difuminado sutil en el borde izquierdo de transición para garantizar legibilidad del texto
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.50f)
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    cardColor.copy(alpha = 0.92f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }

        // Título limpio alineado abajo a la izquierda sin romper palabras ni recortar texto
        val formattedTitle = remember(item.title) { formatBentoTitle(item.title) }
        val words = remember(formattedTitle) { formattedTitle.trim().split(Regex("[\\s\u00A0]+")) }
        val longestWordLength = remember(words) { words.maxOfOrNull { it.length } ?: 0 }

        val baseFontSize = remember(formattedTitle, longestWordLength) {
            when {
                longestWordLength >= 14 || formattedTitle.length >= 24 -> 11.5.sp
                longestWordLength >= 11 || formattedTitle.length >= 18 -> 12.5.sp
                longestWordLength >= 8 || formattedTitle.length >= 13 -> 13.5.sp
                else -> 15.sp
            }
        }
        val baseLineHeight = remember(baseFontSize) { (baseFontSize.value * 1.25f).sp }

        var currentFontSize by remember(baseFontSize) { mutableStateOf(baseFontSize) }
        var currentLineHeight by remember(baseLineHeight) { mutableStateOf(baseLineHeight) }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxHeight()
                .fillMaxWidth(if (item.thumbnailUrl != null) (if (isWide) 0.68f else 0.64f) else 1f)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = formattedTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = currentFontSize,
                    lineHeight = currentLineHeight,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(0f, 1f),
                        blurRadius = 3f,
                    ),
                    hyphens = Hyphens.None,
                    lineBreak = LineBreak.Heading,
                ),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    if (textLayoutResult.hasVisualOverflow && currentFontSize.value > 10f) {
                        currentFontSize = (currentFontSize.value - 1f).sp
                        currentLineHeight = (currentFontSize.value * 1.22f).sp
                    }
                },
            )
        }
    }
}

private val BENTO_PALETTE = listOf(
    Color(0xFFE11D48), // 0: Rose
    Color(0xFFEA580C), // 1: Tangerine
    Color(0xFFD97706), // 2: Amber
    Color(0xFF16A34A), // 3: Green
    Color(0xFF0D9488), // 4: Teal
    Color(0xFF0284C7), // 5: Sky Ocean
    Color(0xFF2563EB), // 6: Royal Blue
    Color(0xFF4F46E5), // 7: Indigo
    Color(0xFF7C3AED), // 8: Violet
    Color(0xFF9333EA), // 9: Purple
    Color(0xFFBE185D), // 10: Magenta
    Color(0xFFDC2626), // 11: Crimson
    Color(0xFF007A5A), // 12: Emerald
    Color(0xFF059669), // 13: Mint
    Color(0xFFC2410C), // 14: Terracotta
    Color(0xFFB45309), // 15: Bronze
    Color(0xFF1D4ED8), // 16: Cobalt
    Color(0xFF6B21A8), // 17: Deep Purple
)

private fun preferredColorIndex(title: String): Int {
    val lower = title.lowercase()
    return when {
        lower.contains("energ") || lower.contains("work") || lower.contains("gym") || lower.contains("entren") -> 0
        lower.contains("chill") || lower.contains("relax") || lower.contains("sleep") || lower.contains("dormir") -> 4
        lower.contains("party") || lower.contains("fiest") || lower.contains("dance") || lower.contains("electr") -> 8
        lower.contains("rock") || lower.contains("metal") || lower.contains("punk") -> 11
        lower.contains("pop") || lower.contains("hit") || lower.contains("exito") -> 10
        lower.contains("hip") || lower.contains("urban") || lower.contains("rap") || lower.contains("trap") || lower.contains("reggaet") -> 2
        lower.contains("focus") || lower.contains("concentr") || lower.contains("estudi") || lower.contains("acoust") || lower.contains("indie") -> 12
        lower.contains("r&b") || lower.contains("soul") || lower.contains("groove") -> 17
        lower.contains("jazz") || lower.contains("blues") || lower.contains("clasic") -> 6
        lower.contains("latin") || lower.contains("cumbia") || lower.contains("salsa") -> 14
        else -> -1
    }
}

private fun assignRowColor(
    item: MoodGenre,
    rowIndex: Int,
    colIndex: Int,
    conflictIndices: Set<Int>,
): Pair<Color, Int> {
    val paletteSize = BENTO_PALETTE.size
    val pref = preferredColorIndex(item.title)
    var candidate = if (pref >= 0) pref else ((rowIndex * 7 + colIndex * 9 + item.title.hashCode().let { (it and 0x7FFFFFFF) % paletteSize }) % paletteSize)

    var attempts = 0
    while (attempts < paletteSize) {
        val isConflicting = conflictIndices.any { conflict ->
            val diff = kotlin.math.abs(candidate - conflict)
            val cyclicDiff = kotlin.math.min(diff, paletteSize - diff)
            cyclicDiff < 3
        }
        if (!isConflicting) break
        candidate = (candidate + 5) % paletteSize
        attempts++
    }
    return BENTO_PALETTE[candidate] to candidate
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(18.dp)

const val MOOD_CARD_ASPECT = 1.72f

private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> itemsIndexed(state.data, key = { _, shelf -> shelf.title }) { index, shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
