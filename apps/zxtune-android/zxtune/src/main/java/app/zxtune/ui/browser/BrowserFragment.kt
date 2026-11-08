/**
 * @file
 * @brief Vfs browser fragment component
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.MediaControllerCompat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener
import app.zxtune.MainActivity
import app.zxtune.R
import app.zxtune.ResultActivity
import app.zxtune.ui.utils.SelectionUtils
import app.zxtune.ui.utils.whenLifecycleStarted
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class BrowserFragment :
    Fragment(),
    MainActivity.PagerTabListener {
    private val model by activityViewModels<Model>()
    private val stateStorage by lazy {
        State.create(requireContext())
    }

    private val backHandler by lazy {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = moveUp()
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(this, it)
        }
    }

    private lateinit var location: View
    private lateinit var breadcrumbs: RecyclerView
    private lateinit var listing: RecyclerView
    private lateinit var listingStub: TextView
    private lateinit var search: SearchView

    private var selectionTracker: SelectionTracker<Uri>? = null

    // deep search results are shown instead of location content
    private var hasSearchResults = false

    private val controller
        get() = activity?.let { MediaControllerCompat.getMediaController(it) }
    private val listingLayoutManager
        get() = listing.layoutManager as GridLayoutManager
    private val scope
        get() = viewLifecycleOwner.lifecycleScope
    private val maybeScope
        get() = viewLifecycleOwnerLiveData.value?.lifecycleScope

    private class NotificationView(view: View) {
        private val panel = view.findViewById<TextView>(R.id.browser_notification)

        fun bind(notification: Model.Notification?, onAction: (Intent) -> Unit) {
            setPanelVisibility(notification != null)
            panel.text = notification?.message
            panel.setOnClickListener(notification?.action?.let { intent ->
                View.OnClickListener { onAction(intent) }
            })
        }

        private fun setPanelVisibility(isVisible: Boolean) = panel.run {
            // card is floating above the bottom edge
            val hidden = height + ((layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)
            val translation = if (isVisible) 0f else hidden.toFloat()
            animate().translationY(translation)
        }
    }

    override fun onTabVisibilityChanged(isVisible: Boolean) {
        backHandler.isEnabled = isVisible
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = container?.let { inflater.inflate(R.layout.browser, it, false) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRootsView(view)
        location = view.findViewById(R.id.browser_location)
        breadcrumbs = setupBreadcrumbs(view)
        listing = setupListing(view)
        search = setupSearchView(model, view)
        scope.launch {
            model.initialize(stateStorage.getCurrentPath())
        }
        viewLifecycleOwner.whenLifecycleStarted {
            launch {
                trackState(model.state)
            }
            launch {
                trackProgress(model.progress, view.findViewById(R.id.browser_loading))
            }
            launch {
                trackNotifications(model.notification, NotificationView(view.waitForLayout()))
            }
            launch {
                trackErrors(model.errors)
            }
            launch {
                model.playbackEvents.collect { uri ->
                    controller?.transportControls?.playFromUri(uri, null)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        storeCurrentViewPosition()
    }

    private fun showError(msg: String) = activity?.let {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    private fun setupRootsView(view: View) = view.findViewById<View>(R.id.browser_roots).setOnClickListener { browse(Uri.EMPTY) }

    private fun setupBreadcrumbs(view: View) = view.findViewById<RecyclerView>(R.id.browser_breadcrumb).apply {
        val adapter = BreadcrumbsViewAdapter().apply {
            adapter = this
        }
        addItemDecoration(BreadcrumbsViewAdapter.Delimiters(context))
        val onClick = View.OnClickListener { v: View ->
            val pos = getChildAdapterPosition(v)
            adapter.currentList.getOrNull(pos)?.uri?.let {
                browse(it)
            }
        }
        addOnChildAttachStateChangeListener(object : OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = view.setOnClickListener(onClick)

            override fun onChildViewDetachedFromWindow(view: View) = view.setOnClickListener(null)
        })
    }

    private fun setupListing(view: View) = view.findViewById<RecyclerView>(R.id.browser_content).apply {
        setHasFixedSize(true)
        val adapter = ListingViewAdapter(context).apply {
            adapter = this
        }
        layoutManager = createListingLayout(adapter)
        selectionTracker = SelectionTracker.Builder(
            "browser_selection",
            this,
            ListingViewAdapter.KeyProvider(adapter),
            ListingViewAdapter.DetailsLookup(this),
            StorageStrategy.createParcelableStorage(Uri::class.java)
        ).withSelectionPredicate(SelectionPredicates.createSelectAnything())
            .withOnItemActivatedListener { item, _ ->
                item.selectionKey?.let { browse(it) }
                false // for ripple
            }.build().also {
                adapter.setSelection(it.selection)
                SelectionUtils.install(
                    view.findViewById(R.id.browser_top_panel),
                    it,
                    SelectionClient(adapter)
                )
            }
        listingStub = view.findViewById(R.id.browser_stub)
    }

    // Rows take the whole width, cards of sources are as many in a row as fit
    private fun RecyclerView.createListingLayout(adapter: ListingViewAdapter): GridLayoutManager {
        val manager = GridLayoutManager(context, 1)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.isRoot) 1 else manager.spanCount
        }
        val minCardWidth = resources.getDimensionPixelSize(R.dimen.lhuna_source_card_min_width)
        addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val width = right - left - view.paddingLeft - view.paddingRight
                val columns = (width / minCardWidth).coerceIn(1, MAX_CARDS_COLUMNS)
                if (columns != manager.spanCount) {
                    // span count cannot be changed while layout
                    view.post { manager.spanCount = columns }
                }
            }
        }
        return manager
    }

    private suspend fun trackProgress(src: Flow<Int?>, view: ProgressBar) = src.collect { prg ->
        // idle track would look like a divider
        view.isInvisible = prg == null
        view.isIndeterminate = prg == -1
        view.progress = prg ?: 0
    }

    private suspend fun trackState(src: Flow<Model.State>) = src.collect { state ->
        updateBreadcrumbsState(state)
        updateListingState(state)
    }

    private fun updateBreadcrumbsState(state: Model.State) = breadcrumbs.run {
        if (!isInSearchMode()) {
            // sources do not need to be navigated back to
            location.isVisible = state.breadcrumbs.isNotEmpty()
            (adapter as BreadcrumbsViewAdapter).submitList(state.breadcrumbs)
            smoothScrollToPosition(state.breadcrumbs.size)
        }
    }

    private fun updateListingState(state: Model.State) = listing.run {
        val adapter = adapter as ListingViewAdapter
        if (adapter.isRoot != state.isRoot) {
            adapter.isRoot = state.isRoot
            listingLayoutManager.spanSizeLookup.invalidateSpanIndexCache()
            // cards outer edges are aligned with the search field, rows draw their own insets
            val inset = if (state.isRoot) resources.getDimensionPixelSize(R.dimen.lhuna_cards_inset) else 0
            setPadding(inset, paddingTop, inset, paddingBottom)
        }
        if (isInSearchMode()) {
            adapter.submitList(state.entries)
        } else {
            storeCurrentViewPosition()
            adapter.submitList(state.entries) {
                maybeScope?.launch {
                    val pos = stateStorage.updateCurrentPath(state.uri)
                    setCurrentViewPosition(pos)
                }
            }
        }
        val hasContent = state.entries.isNotEmpty()
        isVisible = hasContent
        listingStub.isVisible = !hasContent
    }

    private suspend fun trackNotifications(src: Flow<Model.Notification?>, view: NotificationView) = src.collect { notification ->
        view.bind(notification) { intent ->
            runCatching {
                requireContext().startActivity(intent)
            }.onFailure { showError((it.cause ?: it).message ?: it.javaClass.name) }
        }
    }

    private suspend fun trackErrors(src: Flow<String>) = src.collect { err ->
        showError(err)
    }

    private fun storeCurrentViewPosition() = listingLayoutManager.findFirstVisibleItemPosition()
        .takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
            // Use MainScope to store data despite fragment destruction
            MainScope().launch {
                stateStorage.updateCurrentPosition(pos)
            }
        }

    private fun setCurrentViewPosition(pos: Int) = listingLayoutManager.scrollToPositionWithOffset(pos, 0)

    private fun setupSearchView(model: Model, view: View) = view.findViewById<SearchView>(R.id.browser_search).apply {
        isSubmitButtonEnabled = false
        setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                model.search(query)
                hasSearchResults = true
                clearFocus()
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                model.filter = query
                // cleared query brings the location content back
                if (query.isEmpty() && hasSearchResults) {
                    hasSearchResults = false
                    post {
                        model.cancelSearch()
                    }
                }
                return true
            }
        })
    }

    // ArchivesService for selection
    private inner class SelectionClient(private val adapter: ListingViewAdapter) : SelectionUtils.Client<Uri> {
        override fun getTitle(count: Int) = resources.getQuantityString(
            R.plurals.items,
            count,
            count
        )

        override val allItems
            get() = adapter.currentList.map(ListingEntry::uri)

        override fun fillMenu(inflater: MenuInflater, menu: Menu) = inflater.inflate(R.menu.browser, menu)

        override fun processMenu(itemId: Int, selection: Selection<Uri>): Boolean {
            when (itemId) {
                R.id.action_add -> addToPlaylist(selection)
                else -> return false
            }
            return true
        }

        // TODO: rework for PlaylistControl usage as a local iface
        private fun addToPlaylist(selection: Selection<Uri>) = ResultActivity.addToPlaylistOrCreateRequestPermissionIntent(
            requireContext(),
            convertSelection(selection)
        )?.let {
            startActivity(it)
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        selectionTracker?.onSaveInstanceState(state)
        search.query?.takeIf { it.isNotEmpty() }?.let { query ->
            state.putString(SEARCH_QUERY_KEY, query.toString())
        }
    }

    override fun onViewStateRestored(state: Bundle?) {
        super.onViewStateRestored(state)
        selectionTracker?.onRestoreInstanceState(state)
        state?.getString(SEARCH_QUERY_KEY)?.takeIf { it.isNotEmpty() }?.let { query ->
            search.run {
                post {
                    setQuery(query, false)
                }
            }
        }
    }

    private fun browse(uri: Uri) = model.browse(uri)

    private fun moveUp() = if (isSearchActive()) {
        hasSearchResults = false
        search.run {
            setQuery("", false)
            clearFocus()
        }
        model.cancelSearch()
    } else {
        browseParent()
    }

    private fun isSearchActive() = isInSearchMode() || hasSearchResults || !search.query.isNullOrEmpty()

    private fun browseParent() = model.browseParent()

    private fun isInSearchMode() = search.hasFocus()

    companion object {
        private const val SEARCH_QUERY_KEY = "search_query"
        private const val MAX_CARDS_COLUMNS = 4

        private fun convertSelection(selection: Selection<Uri>) = selection.iterator().let { iterator ->
            Array<Uri>(selection.size()) {
                iterator.next()
            }
        }
    }
}

private suspend fun View.waitForLayout() = suspendCoroutine { cont ->
    doOnLayout {
        cont.resume(this)
    }
}
