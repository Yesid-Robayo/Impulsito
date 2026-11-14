package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.LocationChoice

/** El nombre visible de una ubicación: el que escribió la persona, o el traducido de la inicial. */
@Composable
fun locationName(location: Location): String = SeedText.displayName(
    LocalResources.current,
    SeedTable.LOCATIONS,
    location.systemKey,
    location.details.name,
)

/** Las ubicaciones como opciones del selector, con su nombre en el idioma de la interfaz. */
@Composable
fun locationChoices(locations: List<Location>): List<LocationChoice> =
    locations.map { LocationChoice(it.id, locationName(it)) }
