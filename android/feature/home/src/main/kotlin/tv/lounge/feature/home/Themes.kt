package tv.lounge.feature.home

import tv.lounge.design.LoungeColors
import tv.lounge.design.LoungeThemeVariant
import tv.lounge.design.colors
import tv.lounge.platform.Prefs

/** The rooms (DESIGN.md §3.1), as Settings and onboarding name them. */
val Prefs.Theme.title: String
    get() = when (this) {
        Prefs.Theme.Dusk -> "Crepúsculo"
        Prefs.Theme.House -> "Casa"
        Prefs.Theme.Hearth -> "Hogar"
        Prefs.Theme.Opera -> "Ópera"
        Prefs.Theme.Marquee -> "Marquee"
        Prefs.Theme.GreenRoom -> "Sala verde"
        Prefs.Theme.Moonlight -> "Luz de luna"
        Prefs.Theme.Graphite -> "Grafito"
        Prefs.Theme.Cabaret -> "Cabaret"
        Prefs.Theme.Aquarium -> "Acuario"
        Prefs.Theme.Absinthe -> "Ajenjo"
        Prefs.Theme.Ultraviolet -> "Ultravioleta"
        Prefs.Theme.Blackout -> "Negro puro"
        Prefs.Theme.HighContrast -> "Alto contraste"
    }

val Prefs.Theme.tagline: String
    get() = when (this) {
        Prefs.Theme.Dusk -> "Noche índigo, lámpara coral"
        Prefs.Theme.House -> "Ciruela cálido, luz tungsteno"
        Prefs.Theme.Hearth -> "Paredes ámbar, luz de fuego"
        Prefs.Theme.Opera -> "Terciopelo rojo y dorado"
        Prefs.Theme.Marquee -> "Navy, marquee bulbs"
        Prefs.Theme.GreenRoom -> "Verde profundo, lámpara latón"
        Prefs.Theme.Moonlight -> "Pizarra fría, luz de luna"
        Prefs.Theme.Graphite -> "Sin color, blanco cálido"
        Prefs.Theme.Cabaret -> "Violeta oscuro, neón rosa"
        Prefs.Theme.Aquarium -> "Verde azulado profundo"
        Prefs.Theme.Absinthe -> "Oliva oscuro, brillo verde oro"
        Prefs.Theme.Ultraviolet -> "Casi negro, luz ultravioleta"
        Prefs.Theme.Blackout -> "Negro real para pantallas OLED"
        Prefs.Theme.HighContrast -> "Texto y bordes más marcados"
    }

fun Prefs.Theme.variant(): LoungeThemeVariant = LoungeThemeVariant.valueOf(name)

fun Prefs.Theme.palette(): LoungeColors = variant().colors()
