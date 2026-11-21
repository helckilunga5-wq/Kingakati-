package com.guestkingakati.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Gold = Color(0xFFE1B85B)
private val Dark = Color(0xFF11110E)
private val Panel = Color(0xFF1D1C17)
private val Cream = Color(0xFFF8F5EC)

private const val WHATSAPP_NUMBER = "243834989934"

// Collez ici le lien CSV de votre Google Sheet publié (voir LISEZ-MOI.md).
// Tant que ce lien est vide, l'application affiche les données de secours ci-dessous.
private const val SHEET_CSV_URL = ""

private val ONLINE_ENABLED = SHEET_CSV_URL.isNotBlank()

/* ------------------------------------------------------------------ */
/*  Catalogue (chambres, plats, boissons) lu depuis Google Sheets      */
/* ------------------------------------------------------------------ */

data class CatalogItem(
    val category: String,   // Chambre, Plat ou Boisson
    val name: String,
    val description: String,
    val priceUsd: String,
    val priceFc: String,
    val photoUrl: String,
    val available: Boolean
)

object Catalog {
    private const val PREFS = "guest_kingakati"
    private const val KEY = "catalog_csv"

    // Données de secours (hors connexion, ou lien non configuré)
    val fallback = listOf(
        CatalogItem(
            category = "Chambre",
            name = "Chambre climatisée",
            description = "Chambre climatisée avec petit déjeuner.",
            priceUsd = "60",
            priceFc = "145000",
            photoUrl = "",
            available = true
        )
    )

    fun cached(context: Context): List<CatalogItem>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return parse(raw).takeIf { it.isNotEmpty() }
    }

    suspend fun fetch(context: Context): List<CatalogItem>? {
        if (SHEET_CSV_URL.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(SHEET_CSV_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val text = try {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val items = parse(text)
                if (items.isEmpty()) {
                    null
                } else {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY, text)
                        .apply()
                    items
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Colonnes : categorie, nom, description, prix_usd, prix_fc, photo_url, disponible
    fun parse(csv: String): List<CatalogItem> {
        return parseCsv(csv).drop(1).mapNotNull { row ->
            fun col(i: Int): String = row.getOrNull(i)?.trim().orEmpty()

            val name = col(1)
            if (name.isEmpty()) return@mapNotNull null

            val dispo = col(6).lowercase(Locale.ROOT)
            CatalogItem(
                category = col(0),
                name = name,
                description = col(2),
                priceUsd = col(3),
                priceFc = col(4),
                photoUrl = col(5),
                available = dispo !in listOf("non", "no", "0", "false", "faux")
            )
        }
    }
}

fun parseCsv(text: String): List<List<String>> {
    val s = text.removePrefix("\uFEFF")
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < s.length) {
        val c = s[i]
        if (inQuotes) {
            if (c == '"' && i + 1 < s.length && s[i + 1] == '"') {
                field.append('"')
                i++
            } else if (c == '"') {
                inQuotes = false
            } else {
                field.append(c)
            }
        } else if (c == '"') {
            inQuotes = true
        } else if (c == ',') {
            row.add(field.toString())
            field.setLength(0)
        } else if (c == '\n' || c == '\r') {
            if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
            row.add(field.toString())
            field.setLength(0)
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = mutableListOf()
        } else {
            field.append(c)
        }
        i++
    }

    row.add(field.toString())
    if (row.any { it.isNotBlank() }) rows.add(row)
    return rows
}

fun formatPrice(usd: String, fc: String): String {
    val parts = mutableListOf<String>()

    val usdClean = usd.trim().removeSuffix("$").trim()
    if (usdClean.isNotEmpty()) parts.add("$usdClean $")

    val fcDigits = fc.filter { it.isDigit() }.toLongOrNull()
    if (fcDigits != null) {
        parts.add(String.format(Locale.FRANCE, "%,d", fcDigits) + " FC")
    } else if (fc.isNotBlank()) {
        parts.add(fc.trim())
    }

    return if (parts.isEmpty()) "Tarif à confirmer" else parts.joinToString(" ou ")
}

/* ------------------------------------------------------------------ */
/*  Activité                                                           */
/* ------------------------------------------------------------------ */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GuestKingakatiApp()
        }
    }
}

private val navItems = listOf(
    "Accueil" to "🏠",
    "Hôtel" to "🛏️",
    "Restaurant" to "🍽️",
    "Réserver" to "📅",
    "Mes demandes" to "📋"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestKingakatiApp() {
    val context = LocalContext.current

    var page by rememberSaveable { mutableStateOf("Accueil") }
    var presetService by rememberSaveable { mutableStateOf("Chambre") }

    var catalog by remember { mutableStateOf(Catalog.cached(context) ?: Catalog.fallback) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        Catalog.fetch(context)?.let { catalog = it }
        loading = false
    }

    fun go(target: String, service: String? = null) {
        if (service != null) presetService = service
        page = target
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            background = Dark,
            surface = Panel,
            onPrimary = Dark,
            onBackground = Cream,
            onSurface = Cream
        )
    ) {
        Scaffold(
            containerColor = Dark,
            contentColor = Cream,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    navItems.forEach { (label, emoji) ->
                        NavigationBarItem(
                            selected = page == label,
                            onClick = { page = label },
                            icon = { Text(emoji, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text(
                    "GUEST KINGAKATI",
                    color = Gold,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "SÉJOURNEZ • SAVOUREZ • CÉLÉBREZ",
                    color = Cream,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    when (page) {
                        "Accueil" -> HomePage(
                            onNavigate = { target, service -> go(target, service) }
                        )
                        "Hôtel" -> HotelPage(
                            rooms = catalog.filter {
                                it.category.equals("Chambre", ignoreCase = true) && it.available
                            },
                            loading = loading,
                            onRefresh = { refreshKey++ },
                            onReserve = { go("Réserver", "Chambre") }
                        )
                        "Restaurant" -> RestaurantPage(
                            dishes = catalog.filter {
                                it.category.equals("Plat", ignoreCase = true) && it.available
                            },
                            drinks = catalog.filter {
                                it.category.equals("Boisson", ignoreCase = true) && it.available
                            },
                            loading = loading,
                            onRefresh = { refreshKey++ },
                            onReserve = { go("Réserver", "Restaurant") }
                        )
                        "Réserver" -> BookingPage(initialService = presetService)
                        "Mes demandes" -> HistoryPage()
                    }
                }
            }
        }
    }
}
/* ------------------------------------------------------------------ */
/*  Accueil                                                            */
/* ------------------------------------------------------------------ */

@Composable
fun HomePage(onNavigate: (page: String, service: String?) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("VOTRE DESTINATION DE DÉTENTE", color = Gold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Bienvenue chez\nGuest Kingakati",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Cream
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Un lieu pour séjourner, savourer, célébrer et se ressourcer.",
                        color = Cream
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { onNavigate("Réserver", null) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Gold,
                            contentColor = Dark
                        )
                    ) {
                        Text("Réserver maintenant")
                    }
                }
            }
        }

        item {
            ServiceCard(
                title = "Hôtel",
                description = "Découvrez nos chambres climatisées avec petit déjeuner.",
                onClick = { onNavigate("Hôtel", null) }
            )
        }

        item {
            ServiceCard(
                title = "Restaurant",
                description = "Cuisine chinoise, européenne, africaine et italienne.",
                onClick = { onNavigate("Restaurant", null) }
            )
        }

        item {
            ServiceCard(
                title = "Événements",
                description = "Mariages, anniversaires et autres manifestations : " +
                    "salle VIP et jardin pour vos célébrations et réunions.",
                buttonLabel = "Faire une demande",
                onClick = { onNavigate("Réserver", "Mariage") }
            )
        }

        item {
            ServiceCard(
                title = "Loisirs",
                description = "Jeux pour enfants, chaise de massage et plusieurs autres activités de détente.",
                buttonLabel = "Faire une demande",
                onClick = { onNavigate("Réserver", "Loisirs") }
            )
        }
    }
}

@Composable
fun ServiceCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    buttonLabel: String = "Découvrir",
    price: String? = null,
    imageUrl: String? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                )
            }
            Column(Modifier.padding(20.dp)) {
                Text(title, color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(description, color = Cream)
                }
                if (price != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(price, color = Gold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClick) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

@Composable
fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    if (!ONLINE_ENABLED) return
    OutlinedButton(onClick = onRefresh, enabled = !loading) {
        Text(if (loading) "Mise à jour…" else "Actualiser les tarifs")
    }
}

/* ------------------------------------------------------------------ */
/*  Hôtel                                                              */
/* ------------------------------------------------------------------ */

@Composable
fun HotelPage(
    rooms: List<CatalogItem>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onReserve: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Hôtel & chambres", color = Gold, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text("Disponibilités à confirmer auprès de l'établissement.", color = Cream)
            Spacer(Modifier.height(4.dp))
            Text(
                "Une salle d'attente est à la disposition des visiteurs de nos clients.",
                color = Cream
            )
            Spacer(Modifier.height(8.dp))
            RefreshButton(loading, onRefresh)
        }

        if (rooms.isEmpty()) {
            item { Text("Aucune chambre à afficher pour le moment.", color = Cream) }
        }

        items(rooms) { room ->
            ServiceCard(
                title = room.name,
                description = room.description,
                price = formatPrice(room.priceUsd, room.priceFc) + " la chambre",
                imageUrl = room.photoUrl,
                buttonLabel = "Réserver",
                onClick = onReserve
            )
        }

        item {
            Button(
                onClick = onReserve,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Dark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Demander une réservation")
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Restaurant                                                         */
/* ------------------------------------------------------------------ */

@Composable
fun MenuRow(item: CatalogItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (item.photoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.photoUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = Cream, fontWeight = FontWeight.Bold)
                    if (item.description.isNotBlank()) {
                        Text(item.description, color = Cream, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(formatPrice(item.priceUsd, item.priceFc), color = Gold)
            }
        }
    }
}

@Composable
fun RestaurantPage(
    dishes: List<CatalogItem>,
    drinks: List<CatalogItem>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onReserve: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Restaurant", color = Gold, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Cuisine chinoise, européenne, africaine et italienne.",
                color = Cream
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Les prix peuvent changer : confirmez auprès de l'établissement.",
                color = Cream
            )
            Spacer(Modifier.height(8.dp))
            RefreshButton(loading, onRefresh)
        }

        if (dishes.isNotEmpty()) {
            item { Text("Plats", color = Gold, fontSize = 22.sp) }
            items(dishes) { MenuRow(it) }
        }

        if (drinks.isNotEmpty()) {
            item { Text("Boissons", color = Gold, fontSize = 22.sp) }
            items(drinks) { MenuRow(it) }
        }

        if (dishes.isEmpty() && drinks.isEmpty()) {
            item { Text("Le menu sera bientôt disponible ici.", color = Cream) }
        }

        item {
            Button(
                onClick = {
                    openWhatsApp(
                        context,
                        "Bonjour Guest Kingakati, je voudrais consulter le menu."
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Dark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Demander le menu sur WhatsApp")
            }
        }

        item {
            OutlinedButton(
                onClick = onReserve,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Réserver une table")
            }
        }
    }
}
/* ------------------------------------------------------------------ */
/*  Réservation                                                        */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingPage(initialService: String) {
    val context = LocalContext.current

    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable(initialService) { mutableStateOf(initialService) }
    var date by rememberSaveable { mutableStateOf("") }
    var guests by rememberSaveable { mutableStateOf("1") }
    var details by rememberSaveable { mutableStateOf("") }

    val phoneOk = isValidPhone(phone)
    val dateOk = isValidDate(date)
    val guestsOk = guests.toIntOrNull()?.let { it in 1..500 } == true
    val formOk = name.isNotBlank() && phoneOk && dateOk && guestsOk

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Demande de réservation", color = Gold, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Votre demande sera envoyée par WhatsApp. " +
                    "Elle sera confirmée après réponse de l'établissement.",
                color = Cream
            )
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom complet") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = phone,
                onValueChange = { v ->
                    if (v.length <= 20 && v.all { it.isDigit() || it in "+ -()" }) phone = v
                },
                label = { Text("Téléphone") },
                placeholder = { Text("+243 ...") },
                singleLine = true,
                isError = phone.isNotEmpty() && !phoneOk,
                supportingText = if (phone.isNotEmpty() && !phoneOk) {
                    { Text("Numéro invalide (9 à 15 chiffres).") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text("Service", color = Gold)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "Chambre",
                    "Restaurant",
                    "Mariage",
                    "Anniversaire",
                    "Autre événement",
                    "Loisirs"
                ).forEach {
                    FilterChip(
                        selected = service == it,
                        onClick = { service = it },
                        label = { Text(it) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = date,
                onValueChange = { v ->
                    if (v.length <= 10 && v.all { it.isDigit() || it == '/' }) date = v
                },
                label = { Text("Date souhaitée") },
                placeholder = { Text("JJ/MM/AAAA") },
                singleLine = true,
                isError = date.isNotEmpty() && !dateOk,
                supportingText = if (date.isNotEmpty() && !dateOk) {
                    { Text("Date invalide ou passée (format JJ/MM/AAAA).") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = guests,
                onValueChange = { v ->
                    if (v.length <= 3 && v.all { it.isDigit() }) guests = v
                },
                label = { Text("Nombre de personnes") },
                singleLine = true,
                isError = !guestsOk,
                supportingText = if (!guestsOk) {
                    { Text("Entrez un nombre entre 1 et 500.") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Précisions") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = {
                    val message = listOf(
                        "Bonjour Guest Kingakati,",
                        "je souhaite faire une demande de réservation.",
                        "",
                        "Nom : ${name.trim()}",
                        "Téléphone : ${phone.trim()}",
                        "Service : $service",
                        "Date : ${date.trim()}",
                        "Personnes : $guests",
                        "Précisions : ${details.trim()}"
                    ).joinToString("\n")

                    HistoryStore.add(
                        context,
                        "$service • ${date.trim()} • $guests pers. • ${name.trim()}"
                    )
                    openWhatsApp(context, message)
                },
                enabled = formOk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Dark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Envoyer la demande")
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Historique local des demandes                                      */
/* ------------------------------------------------------------------ */

@Composable
fun HistoryPage() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(HistoryStore.load(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Mes demandes", color = Gold, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Demandes préparées depuis ce téléphone. " +
                    "La confirmation se fait sur WhatsApp.",
                color = Cream
            )
        }

        if (entries.isEmpty()) {
            item { Text("Aucune demande pour le moment.", color = Cream) }
        } else {
            items(entries) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(entry, color = Cream, modifier = Modifier.padding(16.dp))
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        HistoryStore.clear(context)
                        entries = emptyList()
                    }
                ) {
                    Text("Effacer l'historique")
                }
            }
        }
    }
}

object HistoryStore {
    private const val PREFS = "guest_kingakati"
    private const val KEY = "history"
    private const val SEP = "\u001E"

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEP)
    }

    fun add(context: Context, entry: String) {
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
        val clean = "$stamp — $entry".replace(SEP, " ")
        val updated = (listOf(clean) + load(context)).take(50)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, updated.joinToString(SEP))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}

/* ------------------------------------------------------------------ */
/*  Validation                                                         */
/* ------------------------------------------------------------------ */

fun isValidPhone(phone: String): Boolean {
    val digits = phon
  e.count { it.isDigit() }
    return digits in 9..15
}

fun isValidDate(input: String): Boolean {
    val text = input.trim()
    if (!Regex("""\d{1,2}/\d{1,2}/\d{4}""").matches(text)) return false

    val format = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).apply { isLenient = false }
    val parsed = try {
        format.parse(text)
    } catch (e: Exception) {
        null
    } ?: return false

    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    return !parsed.before(today)
}

/* ------------------------------------------------------------------ */
/*  WhatsApp                                                           */
/* ------------------------------------------------------------------ */

fun openWhatsApp(context: Context, message: String) {
    // URLEncoder met des "+" pour les espaces : WhatsApp attend %20
    val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
    val url = "https://wa.me/$WHATSAPP_NUMBER?text=$encoded"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Impossible d'ouvrir WhatsApp. Installez-le ou utilisez un navigateur.",
            Toast.LENGTH_LONG
        ).show()
    }
}
