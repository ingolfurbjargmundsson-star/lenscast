import type { LensInfo } from '../types'
import { t } from '../lib/i18n'

interface Props {
  lenses: () => LensInfo[]
  handleSelectLens: (index: number) => void
}

function fmtFocalLength(fl: number): string {
  if (fl <= 0) return ''
  return Number.isInteger(fl) ? `${fl}mm` : `${fl.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}mm`
}

/**
 * "2x" for whole magnifications, one trimmed decimal otherwise ("2.3x").
 * Language-independent like the focal suffix, so no catalog entry is needed.
 */
function fmtZoomRatio(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio <= 0) return ''
  const rounded = Math.round(ratio * 10) / 10
  const text = Number.isInteger(rounded) ? `${rounded}` : `${rounded.toFixed(1)}`
  return `${text}x`
}

/**
 * The chip label, localized from the lens's display role — the backend hands
 * down a kind plus zoom ratio and an English base label, and the dashboard
 * builds its own string so the chips follow the dashboard language like the
 * rest of the UI.
 */
function lensLabel(lens: LensInfo): string {
  const frontVariant = (variant: string) => t('lens.frontVariant', { variant })
  const zoomText = (lens.zoomRatio != null ? fmtZoomRatio(lens.zoomRatio) : '') || fmtFocalLength(lens.focalLength)
  switch (lens.kind) {
    case 'FRONT':
      return t('lens.front')
    case 'BACK':
      return t('lens.back')
    case 'ULTRAWIDE':
      return lens.isFront ? t('lens.frontUltrawide') : t('lens.ultrawide')
    case 'WIDE':
      return t('lens.wide')
    case 'ZOOM':
      return lens.isFront ? frontVariant(zoomText) : zoomText
    case 'FOCAL_MM':
      return lens.isFront ? frontVariant(fmtFocalLength(lens.focalLength)) : fmtFocalLength(lens.focalLength)
    case 'CAMERA_ID':
      return t('lens.cameraId', { id: lens.id })
    default:
      // Older backend without kinds — the English base label.
      return lens.label
  }
}

export default function LensSelector(props: Props) {
  return (
    <div class="lens-selector" id="lens-selector">
      {props.lenses().map((lens) => (
        <button
          class="lens-btn"
          classList={{ 'lens-btn-active': lens.selected }}
          onClick={() => props.handleSelectLens(lens.index)}
        >
          <span class="lens-btn-label">{lensLabel(lens)}</span>
          <span class="lens-btn-focal">{fmtFocalLength(lens.focalLength)}</span>
        </button>
      ))}
    </div>
  )
}
