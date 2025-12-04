package com.sample.edgedetection.crop

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.R
import com.sample.edgedetection.base.BaseActivity
import com.sample.edgedetection.view.PaperRectangle

class CropActivity : BaseActivity(), ICropView.Proxy {

    private var showMenuItems = false

    private lateinit var mPresenter: CropPresenter

    private lateinit var initialBundle: Bundle

    override fun prepare() {
        this.initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle
        this.title = initialBundle.getString(EdgeDetectionHandler.CROP_TITLE)
        
        // Set action bar styling for proper visibility
        supportActionBar?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(resources.getColor(R.color.colorPrimary)))
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(android.R.drawable.ic_menu_close_clear_cancel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(R.id.paper).post {
            // we have to initialize everything in post when the view has been drawn and we have the actual height and width of the whole view
            mPresenter.onViewsReady(findViewById<View>(R.id.paper).width, findViewById<View>(R.id.paper).height)
        }
    }

    override fun provideContentViewId(): Int = R.layout.activity_crop


    override fun initPresenter() {
        val initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle
        mPresenter = CropPresenter(this, initialBundle)
    }

    override fun getPaper(): ImageView = findViewById(R.id.paper)

    override fun getPaperRect() = findViewById<PaperRectangle>(R.id.paper_rect)

    override fun getCroppedPaper() = findViewById<ImageView>(R.id.picture_cropped)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.crop_activity_menu, menu)

        menu.findItem(R.id.crop_action).isVisible = !showMenuItems
        menu.findItem(R.id.action_label).isVisible = showMenuItems
        menu.findItem(R.id.rotation_image).isVisible = showMenuItems
        menu.findItem(R.id.gray).isVisible = showMenuItems
        menu.findItem(R.id.reset).isVisible = showMenuItems

        menu.findItem(R.id.gray).title =
            initialBundle.getString(EdgeDetectionHandler.CROP_BLACK_WHITE_TITLE) as String
        menu.findItem(R.id.reset).title =
            initialBundle.getString(EdgeDetectionHandler.CROP_RESET_TITLE) as String

        return super.onCreateOptionsMenu(menu)
    }


    private fun changeMenuVisibility(showMenuItems: Boolean) {
        this.showMenuItems = showMenuItems
        invalidateOptionsMenu()
    }

    // handle button activities
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.crop_action -> {
                Log.e(TAG, "Crop action touched!")
                mPresenter.crop()
                changeMenuVisibility(true)
                return true
            }
            R.id.action_label -> {
                Log.e(TAG, "Saved touched!")
                item.isEnabled = false
                mPresenter.save()
                setResult(Activity.RESULT_OK)
                System.gc()
                finish()
                return true
            }
            R.id.rotation_image -> {
                Log.e(TAG, "Rotate touched!")
                mPresenter.rotate()
                return true
            }
            R.id.gray -> {
                Log.e(TAG, "Black White touched!")
                mPresenter.enhance()
                return true
            }
            R.id.reset -> {
                Log.e(TAG, "Reset touched!")
                mPresenter.reset()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }
}
