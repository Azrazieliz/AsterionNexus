package com.ailm.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ailm.android.startup.AppBootstrap
import com.ailm.android.ui.navigation.AppDestination
import com.ailm.android.ui.viewmodel.AppViewModel

/** Directly reuse the complete Core ViewModel and native content screens.
 * Nexus owns the only permanent navigation; no donor Activity or NavHost.
 */
@Composable
fun NexusCorePane(section:String,onRoute:(String)->Unit){
    val context=LocalContext.current
    val vm:AppViewModel=viewModel()
    val root=when(section){
        "CoreGallery"->AppDestination.LibraryBrowser
        "Collections"->AppDestination.Collections
        "Taxonomy"->AppDestination.Tags
        "Review"->AppDestination.ReviewQueue
        "Intelligence"->AppDestination.PluginManager
        else->AppDestination.Automation
    }
    var active by rememberSaveable(section){mutableStateOf(root.name)}
    var bootReady by remember{mutableStateOf(AppBootstrap.isInitializationCompleted())}
    LaunchedEffect(Unit){
        AppBootstrap.initializeApplication(context.applicationContext)
        AppBootstrap.awaitInitialization()
        bootReady=true
    }
    if(!bootReady||!AppBootstrap.isRuntimeInitialized()){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            if(!bootReady)CircularProgressIndicator()
            else Text(AppBootstrap.initializationFailure()?:"Core runtime unavailable; saved data retained")
        }
        return
    }
    val screen=AppDestination.valueOf(active)
    BackHandler(enabled=screen!=root){active=root.name}
    fun navigate(next:AppDestination){
        when(next){
            AppDestination.Dashboard,AppDestination.LibraryBrowser->onRoute("CoreGallery")
            AppDestination.Collections->onRoute("Collections")
            AppDestination.Automation->onRoute("Activity")
            AppDestination.ReviewQueue->onRoute("Review")
            AppDestination.Tags,AppDestination.KnowledgePacks->onRoute("Taxonomy")
            AppDestination.Downloads->onRoute("Cloud")
            // Existing Core specialist views are contextual, not new global tabs.
            else->active=next.name
        }
    }
    ScreenScaffold(destination=screen,onNavigate=::navigate,appViewModel=vm)
}
