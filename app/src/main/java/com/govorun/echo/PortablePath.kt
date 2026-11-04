package com.govorun.echo

/** One path contract for archive members and crash-recovery journals. */
internal object PortablePath {
    fun safe(relative:String)=relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\') && relative.split('/').none{it.isBlank() || it=="." || it==".."} && !relative.contains('\u0000')
}
