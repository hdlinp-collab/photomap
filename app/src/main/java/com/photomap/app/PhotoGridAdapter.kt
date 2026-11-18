package com.photomap.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView

/** 永遠是正方形的 ImageView。 */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** 同一個拍攝點的多張相片，以格狀顯示。 */
class PhotoGridAdapter(
    private val photos: List<Photo>,
    private val thumbs: ThumbnailLoader,
    private val onClick: (Photo) -> Unit,
) : RecyclerView.Adapter<PhotoGridAdapter.Holder>() {

    class Holder(val image: SquareImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val pad = (2 * parent.resources.displayMetrics.density).toInt()
        val iv = SquareImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setPadding(pad, pad, pad, pad)
        }
        return Holder(iv)
    }

    override fun getItemCount() = photos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val photo = photos[position]
        holder.image.tag = photo.id
        holder.image.setImageBitmap(null)
        holder.image.setBackgroundColor(0xFFE0E0E0.toInt())
        holder.image.setOnClickListener { onClick(photo) }
        thumbs.load(photo) { bmp ->
            if (holder.image.tag == photo.id) holder.image.setImageBitmap(bmp)
        }
    }
}
