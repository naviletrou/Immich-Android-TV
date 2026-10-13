package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R

/** Order of the people list. */
enum class PeopleOrder : EnumWithTitle {
    MOST_PHOTOS {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.people_order_most_photos)
    },
    NAME_ASC {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.people_order_name_asc)
    },
    NAME_DESC {
        override fun getTitle(): String = ImmichApplication.appContext!!.getString(R.string.people_order_name_desc)
    };

    companion object {
        fun valueOfSafe(name: String, default: PeopleOrder): PeopleOrder {
            return entries.find { it.toString() == name } ?: default
        }
    }
}
