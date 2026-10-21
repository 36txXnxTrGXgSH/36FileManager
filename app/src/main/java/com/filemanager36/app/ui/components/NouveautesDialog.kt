package com.filemanager36.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filemanager36.app.data.GenreNote
import com.filemanager36.app.data.LigneNote
import com.filemanager36.app.data.VersionNote

/**
 * « Ce qui a changé », à l'ouverture d'une version fraîchement installée.
 *
 * Affichée **une fois par version**, jamais à la première installation, et rouvrable d'un appui sur le
 * numéro de version de l'accueil. Le contenu vient de `Changelog`, seule source de vérité.
 *
 * La liste défile : une mise à jour qui en saute plusieurs peut en afficher beaucoup, et un
 * dialogue qui déborde de l'écran sans défilement cache son propre bouton de fermeture.
 */
@Composable
fun NouveautesDialog(
    versions: List<VersionNote>,
    onFermer: () -> Unit
) {
    if (versions.isEmpty()) return

    AlertDialog(
        onDismissRequest = onFermer,
        title = {
            Text(
                if (versions.size == 1) "Nouveautés de la version ${versions.first().nom}"
                else "Ce qui a changé depuis votre dernière version"
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // L'en-tête de version n'est utile que s'il y en a plusieurs : sur une
                        // seule, le titre du dialogue le dit déjà.
                        if (versions.size > 1) {
                            Text(
                                "Version ${version.nom} · ${version.date}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        version.lignes.forEach { LigneDeNote(it) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onFermer) { Text("D'accord") } }
    )
}

@Composable
private fun LigneDeNote(ligne: LigneNote) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Pastille(ligne.genre)
        Text(
            ligne.texte,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Nouveau, amélioré ou corrigé.
 *
 * La couleur **et** le mot : une pastille qui ne se distinguerait que par sa teinte ne dirait
 * rien à qui distingue mal les couleurs, ni sur une capture en noir et blanc.
 */
@Composable
private fun Pastille(genre: GenreNote) {
    val (libelle, teinte) = when (genre) {
        GenreNote.NOUVEAU -> "Nouveau" to MaterialTheme.colorScheme.primary
        GenreNote.AMELIORE -> "Amélioré" to MaterialTheme.colorScheme.tertiary
        GenreNote.CORRIGE -> "Corrigé" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = teinte.copy(alpha = 0.14f),
        contentColor = teinte,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 20.dp)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                libelle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
