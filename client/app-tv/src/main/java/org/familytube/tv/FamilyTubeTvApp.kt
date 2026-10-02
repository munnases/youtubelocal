package org.familytube.tv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import javax.inject.Inject
import org.familytube.core.data.NetworkClient

@HiltAndroidApp
class FamilyTubeTvApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var network: NetworkClient
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { network.http })) }
        .build()
}
