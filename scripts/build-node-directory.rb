# Regenerates `app/src/main/assets/lnnodes.bin`, the bundled pubkey -> alias
# directory that `core/lightning/NodeDirectory.kt` reads.
#
# Run it on a developer machine, never on the phone. The point of the asset is
# that the app answers "who is this peer" without asking anyone: handing a
# channel's remote pubkey to an explorer maps the node's topology for whoever is
# watching. So the network call happens here, once, against data that describes
# the whole public graph rather than the operator's peers.
#
#   ruby scripts/build-node-directory.rb
#   MEMPOOL=https://mempool.emzy.de ruby scripts/build-node-directory.rb
#
# Source: a mempool.space instance's Lightning module, which serves the graph
# per country. That covers announced clearnet nodes; Tor-only nodes carry no
# geolocation and are reachable here only through the top-100 rankings, which
# are fetched as well. The gap is real and is documented in the README's
# "Lightning node names" section rather than papered over.
#
# Next to this script it writes `lnnodes.manifest.json`: where the data came
# from, when, which version of this script made it, and the asset's SHA-256.
# It stays out of the APK. NodeDirectoryTest checks the hash, so an asset
# edited by hand, or regenerated without the manifest, fails the build.
#
# Everything below is stdlib.

require 'digest'
require 'json'
require 'net/http'
require 'uri'
require 'set'

ROOT = File.expand_path('..', __dir__)
API = "#{ENV.fetch('MEMPOOL', 'https://mempool.space')}/api/v1/lightning".freeze
OUT = File.join(ROOT, 'app/src/main/assets/lnnodes.bin')
MANIFEST = File.join(ROOT, 'scripts/lnnodes.manifest.json')
CURATED_KT = File.join(ROOT, 'app/src/main/java/com/btcpayapp/core/lightning/NodeDirectory.kt')

# Format version. Bump only together with NodeIndex.kt.
VERSION = 1
# Bytes of pubkey stored per record. 16 bytes is 128 bits: a stranger's key
# sharing a prefix with a listed one is not something that happens by accident,
# and grinding one deliberately to inherit a name costs 2^128. Storing all 33
# bytes would add ~120 KB to the APK to defend against nothing.
KEY_BYTES = 16
NAME_MAX = 40
HEADER_BYTES = 24

# Aliases are self-declared and not unique. Two nodes calling themselves the
# same thing are kept only when one is overwhelmingly the larger — otherwise
# both are dropped and the reader gets a shortened pubkey, which is honest.
DOMINANCE = 10
DOMINANT_MIN_CHANNELS = 10

# A modest confusable fold, so "Кraken" (Cyrillic К) collides with "Kraken" and
# both are dropped by the rule above. Not a complete UTS-39 table; it covers the
# Cyrillic and Greek letters that look exactly like Latin ones in a sans face.
CONFUSABLES = {
  'а' => 'a', 'в' => 'b', 'с' => 'c', 'е' => 'e', 'н' => 'h', 'к' => 'k',
  'м' => 'm', 'о' => 'o', 'р' => 'p', 'т' => 't', 'у' => 'y', 'х' => 'x',
  'ѕ' => 's', 'і' => 'i', 'ј' => 'j', 'ԁ' => 'd', 'ɡ' => 'g', 'ӏ' => 'l',
  'α' => 'a', 'β' => 'b', 'ε' => 'e', 'ι' => 'i', 'κ' => 'k', 'ν' => 'v',
  'ο' => 'o', 'ρ' => 'p', 'σ' => 'o', 'τ' => 't', 'υ' => 'u', 'χ' => 'x',
  'ѐ' => 'e', 'ё' => 'e', 'ⅼ' => 'l', 'ⅰ' => 'i'
}.freeze

# Names a scammer gains most from wearing: the exchanges and payment services a
# merchant sends money to. Renaming a one-channel node "okx.com" is free;
# funding a large node is not. So an alias that merely *contains* one of these
# is kept only on a node with at least DOMINANT_MIN_CHANNELS channels and
# BRAND_MIN_CAPACITY sat — the brands' own extra nodes ("Bitrefill Routing",
# "River Financial 2") pass, a fresh look-alike does not.
BRAND_TOKENS = %w[
  acinq binance bitfinex bitgo bitrefill blink coinbase coingate fixedfloat
  kraken lnmarkets nicehash okx opennode paxful river strike walletofsatoshi
].freeze
BRAND_MIN_CAPACITY = 100_000_000

def get(path)
  uri = URI("#{API}#{path}")
  3.times do |attempt|
    begin
      response = Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == 'https', open_timeout: 15, read_timeout: 120) do |http|
        http.get(uri.request_uri, 'User-Agent' => 'btcpayapp-node-directory/1')
      end
      return JSON.parse(response.body) if response.is_a?(Net::HTTPSuccess)
      warn "  #{path}: HTTP #{response.code}#{attempt < 2 ? ', retrying' : ''}"
    rescue Timeout::Error, SocketError, SystemCallError, OpenSSL::SSL::SSLError => e
      # A dropped connection costs one request, not the whole run.
      warn "  #{path}: #{e.class}#{attempt < 2 ? ', retrying' : ''}"
    end
    sleep(2 * (attempt + 1))
  end
  nil
end

# --- Collect ----------------------------------------------------------------

raw = {}

def absorb(raw, nodes)
  Array(nodes).each do |node|
    key = node['public_key'] || node['publicKey']
    next unless key.is_a?(String) && key.match?(/\A0[23][0-9a-f]{64}\z/i)

    key = key.downcase
    entry = raw[key] ||= { 'alias' => nil, 'capacity' => 0, 'channels' => 0 }
    entry['alias'] = node['alias'] if node['alias'].is_a?(String) && !node['alias'].strip.empty?
    entry['capacity'] = [entry['capacity'], node['capacity'].to_i].max
    entry['channels'] = [entry['channels'], (node['channels'] || node['channelcount']).to_i].max
  end
end

countries = get('/nodes/countries') or abort("Cannot reach #{API} — set MEMPOOL to a reachable instance")
puts "#{countries.size} countries, #{countries.sum { |c| c['count'].to_i }} geolocated nodes"

countries.sort_by { |c| -c['count'].to_i }.each do |country|
  iso = country['iso']
  payload = get("/nodes/country/#{iso}")
  unless payload
    warn "  skipped #{iso}"
    next
  end
  absorb(raw, payload.is_a?(Hash) ? payload['nodes'] : payload)
  print "\r  fetched #{iso.ljust(3)} — #{raw.size} nodes"
end
puts

# Tor-only nodes have no country. The rankings are the only bulk list that
# reaches them, and they are the ones worth reaching: a merchant opens channels
# to large routing nodes, not to the long tail.
%w[liquidity connectivity age].each do |ranking|
  absorb(raw, get("/nodes/rankings/#{ranking}"))
end
rankings = get('/nodes/rankings')
absorb(raw, rankings['topByCapacity']) if rankings
absorb(raw, rankings['topByChannels']) if rankings
puts "#{raw.size} nodes after rankings"

# --- Clean ------------------------------------------------------------------

def sanitise(alias_text)
  text = alias_text.to_s.unicode_normalize(:nfc)
  # Cc and Cf strip control characters and the format characters that make a
  # name render as something other than what it is: RTL overrides, zero-width
  # joiners, the interlinear annotation marks.
  text = text.gsub(/[\p{Cc}\p{Cf}]/, '')
  text = text.gsub(/\s+/, ' ').strip
  text = text[0, NAME_MAX].to_s.strip
  text
end

# The comparison form of a name, never shown. NFKC first turns full-width
# ("Ｋｒａｋｅｎ") and mathematical ("𝐊𝐫𝐚𝐤𝐞𝐧") letters into plain ASCII; the
# table then folds the Cyrillic and Greek look-alikes that NFKC leaves alone.
def fold(name)
  name.unicode_normalize(:nfkc).downcase.chars.map { |c| CONFUSABLES.fetch(c, c) }.join.gsub(/[^[:alnum:]]/, '')
end

def wears_brand?(candidate)
  return false unless BRAND_TOKENS.any? { |token| candidate[:fold].include?(token) }
  candidate[:channels] < DOMINANT_MIN_CHANNELS || candidate[:capacity] < BRAND_MIN_CAPACITY
end

curated = File.read(CURATED_KT).scan(/"([0-9a-f]{66})"\s+to\s+"((?:[^"\\]|\\.)*)"/).to_h
abort 'Parsed no curated entries from NodeDirectory.kt — check the regex' if curated.empty?
curated_folds = curated.values.map { |name| fold(name) }.to_set
puts "#{curated.size} curated names kept in Kotlin (they win over this asset)"

stats = Hash.new(0)
candidates = []

raw.each do |pubkey, node|
  name = sanitise(node['alias'])

  if name.empty?
    stats[:no_alias] += 1
    next
  end
  if node['channels'] < 1
    stats[:no_channels] += 1
    next
  end
  # A "name" that is hex, or that the pubkey already contains, says nothing the
  # shortened key does not already say.
  if name.match?(/\A[0-9a-fA-F]{8,}\z/) || pubkey.include?(name.downcase)
    stats[:hex] += 1
    next
  end
  unless name.match?(/[[:alnum:]]/)
    stats[:punctuation] += 1
    next
  end

  candidates << { key: pubkey, name: name, fold: fold(name), capacity: node['capacity'], channels: node['channels'] }
end

# Curated names are hand-verified. Anyone else claiming one is dropped outright
# rather than resolved by capacity: the whole value of the curated layer is
# that "Kraken" on a channel row means Kraken. A brand inside a longer alias is
# dropped unless the node is large (see BRAND_TOKENS).
before = candidates.size
candidates.reject! do |c|
  curated[c[:key]].nil? && (curated_folds.include?(c[:fold]) || wears_brand?(c))
end
stats[:impersonates_curated] = before - candidates.size

kept = []
candidates.group_by { |c| c[:fold] }.each_value do |group|
  if group.size == 1
    kept << group.first
    next
  end
  ranked = group.sort_by { |c| -c[:capacity] }
  leader, runner_up = ranked
  if leader[:capacity] >= DOMINANCE * [runner_up[:capacity], 1].max && leader[:channels] >= DOMINANT_MIN_CHANNELS
    kept << leader
    stats[:ambiguous_resolved] += group.size - 1
  else
    stats[:ambiguous_dropped] += group.size
  end
end

# The curated map is consulted first at runtime, so carrying those pubkeys here
# would only be dead weight.
kept.reject! { |c| curated.key?(c[:key]) }
kept.sort_by! { |c| [c[:key]].pack('H*')[0, KEY_BYTES] }

prefixes = kept.map { |c| [c[:key]].pack('H*')[0, KEY_BYTES] }
if prefixes.size != prefixes.uniq.size
  abort "#{KEY_BYTES}-byte prefix collision — two different nodes would share a name. Raise KEY_BYTES."
end

puts "dropped: #{stats.map { |k, v| "#{k}=#{v}" }.join(' ')}"
puts "keeping #{kept.size} nodes"
abort 'Suspiciously few nodes survived — refusing to overwrite the asset' if kept.size < 1000

# --- Write ------------------------------------------------------------------
#
# magic | version | keyLen | pad | count | generatedDays | indexOffset | namesOffset
# then `count` fixed-width records of (keyLen bytes, u32 name offset), sorted by
# key, then the names blob: each name a u8 length and its UTF-8 bytes. Fixed
# stride and sorted keys are what let the app binary-search the mapped file
# without parsing anything at startup.

names = +''.b
offsets = {}
index = +''.b

kept.each do |node|
  offset = offsets[node[:name]]
  unless offset
    bytes = node[:name].encode('UTF-8').b
    # NAME_MAX characters can be more than 255 bytes only in theory; clamp anyway
    # so the length byte can never overflow.
    bytes = bytes[0, 255]
    offset = names.bytesize
    offsets[node[:name]] = offset
    names << [bytes.bytesize].pack('C') << bytes
  end
  index << [node[:key]].pack('H*')[0, KEY_BYTES] << [offset].pack('N')
end

header = +''.b
header << 'LNND'.b
header << [VERSION, KEY_BYTES, 0, 0].pack('C4')
header << [kept.size].pack('N')
header << [(Time.now.utc.to_i / 86_400)].pack('N')
header << [HEADER_BYTES].pack('N')
header << [HEADER_BYTES + index.bytesize].pack('N')
raise 'header drift' unless header.bytesize == HEADER_BYTES

Dir.mkdir(File.dirname(OUT)) unless Dir.exist?(File.dirname(OUT))
asset = header + index + names
File.binwrite(OUT, asset)
puts "wrote #{OUT} — #{(File.size(OUT) / 1024.0).round(1)} KiB (#{index.bytesize / 1024} KiB index, #{names.bytesize / 1024} KiB names)"

# The script's own blob hash, as git computes it, so the manifest names the
# exact generator even before that version is committed.
script = File.binread(__FILE__)
manifest = {
  'source' => API,
  'generated' => Time.now.utc.strftime('%Y-%m-%d'),
  'script' => "#{File.basename(__FILE__)} blob #{Digest::SHA1.hexdigest("blob #{script.bytesize}\0#{script}")}",
  'nodes' => kept.size,
  'sha256' => Digest::SHA256.hexdigest(asset)
}
File.write(MANIFEST, JSON.pretty_generate(manifest) + "\n")
puts "wrote #{MANIFEST}"
