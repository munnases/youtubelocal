package org.familytube.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import android.content.Context
import javax.inject.Inject
import org.familytube.core.data.NetworkClient

@HiltAndroidApp
class FamilyTubeApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var network: NetworkClient

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { network.http })) }
        .build()
}
