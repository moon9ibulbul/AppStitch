import requests
import re
import json
from urllib.parse import urlparse

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# The GraphQL query provided by the user
QUERY = """
    query viewerInfo($seriesId: Long!, $productId: Long!) {
  viewerInfo(seriesId: $seriesId, productId: $productId) {
    item {
      ...SingleFragment
    }
    seriesItem {
      ...SeriesFragment
    }
    prevItem {
      ...NearItemFragment
    }
    nextItem {
      ...NearItemFragment
    }
    viewerData {
      ...TextViewerData
      ...TalkViewerData
      ...ImageViewerData
      ...VodViewerData
    }
    displayAd {
      ...DisplayAd
    }
  }
}

    fragment SingleFragment on Single {
  id
  productId
  seriesId
  title
  thumbnail
  badge
  isFree
  ageGrade
  state
  slideType
  lastReleasedDate
  size
  pageCount
  isHidden
  remainText
  isWaitfreeBlocked
  saleState
  series {
    ...SeriesFragment
  }
  serviceProperty {
    ...ServicePropertyFragment
  }
  operatorProperty {
    ...OperatorPropertyFragment
  }
  assetProperty {
    ...AssetPropertyFragment
  }
  discountRate
  discountRateText
  isShortsDrama
}


    fragment SeriesFragment on Series {
  id
  seriesId
  title
  thumbnail
  landThumbnail
  categoryUid
  lang
  category
  categoryType
  subcategoryUid
  subcategory
  badge
  isAllFree
  isWaitfree
  ageGrade
  state
  onIssue
  authors
  description
  pubPeriod
  freeSlideCount
  lastSlideAddedDate
  waitfreeBlockCount
  waitfreePeriodByMinute
  bm
  saleState
  startSaleDt
  saleMethod
  discountRate
  discountRateText
  serviceProperty {
    ...ServicePropertyFragment
  }
  operatorProperty {
    ...OperatorPropertyFragment
  }
  assetProperty {
    ...AssetPropertyFragment
  }
  translateProperty {
    ...TranslatePropertyFragment
  }
}


    fragment ServicePropertyFragment on ServiceProperty {
  viewCount
  readCount
  ratingCount
  ratingSum
  commentCount
  pageContinue {
    ...ContinueInfoFragment
  }
  todayGift {
    ...TodayGift
  }
  preview {
    ...PreviewFragment
    ...PreviewFragment
  }
  waitfreeTicket {
    ...WaitfreeTicketFragment
  }
  isAlarmOn
  isLikeOn
  ticketCount
  purchasedDate
  lastViewInfo {
    ...LastViewInfoFragment
  }
  purchaseInfo {
    ...PurchaseInfoFragment
  }
  preview {
    ...PreviewFragment
  }
  ticketInfo {
    price
    discountPrice
    ticketType
  }
}


    fragment ContinueInfoFragment on ContinueInfo {
  title
  isFree
  productId
  lastReadProductId
  scheme
  continueProductType
  hasNewSingle
  hasUnreadSingle
}


    fragment TodayGift on TodayGift {
  id
  uid
  ticketType
  ticketKind
  ticketCount
  ticketExpireAt
  ticketExpiredText
  isReceived
  seriesId
}


    fragment PreviewFragment on Preview {
  item {
    ...PreviewSingleFragment
  }
  nextItem {
    ...PreviewSingleFragment
  }
  usingScroll
}


    fragment PreviewSingleFragment on Single {
  id
  productId
  seriesId
  title
  thumbnail
  badge
  isFree
  ageGrade
  state
  slideType
  lastReleasedDate
  size
  pageCount
  isHidden
  remainText
  isWaitfreeBlocked
  saleState
  operatorProperty {
    ...OperatorPropertyFragment
  }
  assetProperty {
    ...AssetPropertyFragment
  }
}


    fragment OperatorPropertyFragment on OperatorProperty {
  thumbnail
  copy
  helixImpId
  isTextViewer
  selfCensorship
  isBook
  cashInfo {
    discountRate
    setDiscountRate
  }
  ticketInfo {
    price
    discountPrice
    ticketType
  }
}


    fragment AssetPropertyFragment on AssetProperty {
  bannerImage
  cardImage
  cardTextImage
  cleanImage
  ipxVideo
  bannerSet {
    ...BannerSetFragment
  }
  cardSet {
    ...CardSetFragment
  }
  cardCover {
    ...CardCoverFragment
  }
}


    fragment BannerSetFragment on BannerSet {
  backgroundImage
  backgroundColor
  mainImage
  titleImage
}


    fragment CardSetFragment on CardSet {
  backgroundColor
  backgroundImage
}


    fragment CardCoverFragment on CardCover {
  coverImg
  coverRestricted
}


    fragment WaitfreeTicketFragment on WaitfreeTicket {
  chargedPeriod
  chargedCount
  chargedAt
}


    fragment LastViewInfoFragment on LastViewInfo {
  isDone
  lastViewDate
  rate
  spineIndex
}


    fragment PurchaseInfoFragment on PurchaseInfo {
  purchaseType
  rentExpireDate
  expired
}


    fragment TranslatePropertyFragment on TranslateProperty {
  category {
    ...LocaleMapFragment
  }
  sub_category {
    ...LocaleMapFragment
  }
  title {
    ...LocaleMapFragment
  }
  name {
    ...LocaleMapFragment
  }
}


    fragment LocaleMapFragment on LocaleMap {
  ko
  en
  th
}


    fragment NearItemFragment on NearItem {
  productId
  slideType
  ageGrade
  isFree
  title
  thumbnail
}


    fragment TextViewerData on TextViewerData {
  type
  atsServerUrl
  metaSecureUrl
  contentsList {
    chapterId
    contentId
    secureUrl
  }
}


    fragment TalkViewerData on TalkViewerData {
  type
  talkDownloadData {
    dec
    host
    path
    talkViewerType
  }
}


    fragment ImageViewerData on ImageViewerData {
  type
  imageDownloadData {
    ...ImageDownloadData
  }
}


    fragment ImageDownloadData on ImageDownloadData {
  files {
    ...ImageDownloadFile
  }
  totalCount
  totalSize
  viewDirection
  gapBetweenImages
  readType
}


    fragment ImageDownloadFile on ImageDownloadFile {
  no
  size
  secureUrl
  width
  height
}


    fragment VodViewerData on VodViewerData {
  type
  vodDownloadData {
    contentPackId
    drmType
    endpointUrl
    width
    height
    duration
  }
  drmInfo {
    type
    serverType
    error
    fairplayCertificateUrl
    widevineLicenseUrl
    fairplayLicenseUrl
    token
    provider
    assertion
  }
}


    fragment DisplayAd on DisplayAd {
  sectionUid
  bannerUid
  treviUid
  momentUid
}
"""

def extract_ids_from_url(url):
    """
    Extracts seriesId and productId from URL.
    Example: https://page.kakao.com/content/64399835/viewer/64400919
    seriesId = 64399835
    productId = 64400919
    """
    path = urlparse(url).path
    # Try pattern /content/{seriesId}/viewer/{productId}
    m = re.search(r'/content/(\d+)/viewer/(\d+)', path)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None, None

def get_images(url, cookie=None):
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://page.kakao.com/",
        "Content-Type": "application/json",
        "Origin": "https://page.kakao.com"
    }
    if cookie:
        headers["Cookie"] = cookie

    series_id, product_id = extract_ids_from_url(url)

    # Fallback if URL parsing fails but we have at least one ID?
    # Usually the URL structure is consistent.
    if not product_id:
        print(f"KakaoPage: Could not parse IDs from URL: {url}")
        return []

    payload = {
        "query": QUERY,
        "variables": {
            "seriesId": series_id,
            "productId": product_id
        }
    }

    try:
        response = requests.post(
            "https://bff-page.kakao.com/graphql",
            json=payload,
            headers=headers,
            timeout=30
        )
        response.raise_for_status()
        data = response.json()

        # Traverse JSON
        viewer_info = data.get("data", {}).get("viewerInfo", {})
        viewer_data = viewer_info.get("viewerData", {})

        # Check type
        if viewer_data.get("type") == "ImageViewerData":
            files = viewer_data.get("imageDownloadData", {}).get("files", [])
            # Sort by 'no' just in case
            files.sort(key=lambda x: x.get('no', 0))
            urls = [f.get("secureUrl") for f in files if f.get("secureUrl")]
            return urls

        print("KakaoPage: Viewer data type is not ImageViewerData or no data found.")
        return []

    except Exception as e:
        print(f"KakaoPage API Error: {e}")
        return []
