plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false

    // Embeds CPython in the app itself, which is where the agent core
    // will live. Separate from the Termux sandbox on purpose: this runs
    // our own trusted code, in our own process. Model-written code stays
    // on the other side of the UID boundary.
    id("com.chaquo.python") version "17.1.0" apply false
}
