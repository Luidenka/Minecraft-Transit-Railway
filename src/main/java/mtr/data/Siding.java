package mtr.data;

import mtr.config.CustomResources;
import mtr.packet.IPacket;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Consumer;

public class Siding extends SavedRailBase implements IPacket {

	private World world;
	private Depot depot;
	private CustomResources.TrainMapping trainMapping;
	private int trainLength;
	private boolean unlimitedTrains;

	private final float railLength;
	private final List<PathData> path = new ArrayList<>();
	private final List<Float> distances = new ArrayList<>();
	private final List<Float> timePoints = new ArrayList<>();
	private final Set<TrainServer> trains = new HashSet<>();

	private static final String KEY_RAIL_LENGTH = "rail_length";
	private static final String KEY_TRAIN_TYPE = "train_type";
	private static final String KEY_TRAIN_CUSTOM_ID = "train_custom_id";
	private static final String KEY_UNLIMITED_TRAINS = "unlimited_trains";
	private static final String KEY_PATH = "path";
	private static final String KEY_TRAINS = "trains";

	public Siding(long id, BlockPos pos1, BlockPos pos2, float railLength) {
		super(id, pos1, pos2);
		this.railLength = railLength;
		setTrainDetails("", TrainType.values()[0]);
	}

	public Siding(BlockPos pos1, BlockPos pos2, float railLength) {
		super(pos1, pos2);
		this.railLength = railLength;
		setTrainDetails("", TrainType.values()[0]);
	}

	public Siding(NbtCompound nbtCompound) {
		super(nbtCompound);

		railLength = nbtCompound.getFloat(KEY_RAIL_LENGTH);
		TrainType trainType = TrainType.values()[0];
		try {
			trainType = TrainType.valueOf(nbtCompound.getString(KEY_TRAIN_TYPE));
		} catch (Exception ignored) {
		}
		setTrainDetails(nbtCompound.getString(KEY_TRAIN_CUSTOM_ID), trainType);
		unlimitedTrains = nbtCompound.getBoolean(KEY_UNLIMITED_TRAINS);

		final NbtCompound tagPath = nbtCompound.getCompound(KEY_PATH);
		final int pathCount = tagPath.getKeys().size();
		for (int i = 0; i < pathCount; i++) {
			path.add(new PathData(tagPath.getCompound(KEY_PATH + i)));
		}

		final NbtCompound tagTrains = nbtCompound.getCompound(KEY_TRAINS);
		tagTrains.getKeys().forEach(key -> trains.add(new TrainServer(id, railLength, path, distances, timePoints, tagTrains.getCompound(key))));
		generateDistancesAndTimePoints();
	}

	public Siding(PacketByteBuf packet) {
		super(packet);

		railLength = packet.readFloat();
		setTrainDetails(packet.readString(PACKET_STRING_READ_LENGTH), TrainType.values()[packet.readInt()]);
		unlimitedTrains = packet.readBoolean();

		final int pathCount = packet.readInt();
		for (int i = 0; i < pathCount; i++) {
			path.add(new PathData(packet));
		}
		generateDistancesAndTimePoints();
	}

	@Override
	public NbtCompound toCompoundTag() {
		final NbtCompound nbtCompound = super.toCompoundTag();

		nbtCompound.putFloat(KEY_RAIL_LENGTH, railLength);
		nbtCompound.putString(KEY_TRAIN_CUSTOM_ID, trainMapping.customId);
		nbtCompound.putString(KEY_TRAIN_TYPE, trainMapping.trainType.toString());
		nbtCompound.putBoolean(KEY_UNLIMITED_TRAINS, unlimitedTrains);

		RailwayData.writeTag(nbtCompound, path, KEY_PATH);
		RailwayData.writeTag(nbtCompound, trains, KEY_TRAINS);

		return nbtCompound;
	}

	@Override
	public void writePacket(PacketByteBuf packet) {
		super.writePacket(packet);

		packet.writeFloat(railLength);
		packet.writeString(trainMapping.customId);
		packet.writeInt(trainMapping.trainType.ordinal());
		packet.writeBoolean(unlimitedTrains);

		packet.writeInt(path.size());
		path.forEach(pathData -> pathData.writePacket(packet));
	}

	@Override
	public void update(String key, PacketByteBuf packet) {
		switch (key) {
			case KEY_TRAIN_TYPE:
				setTrainDetails(packet.readString(PACKET_STRING_READ_LENGTH), TrainType.values()[packet.readInt()]);
				break;
			case KEY_UNLIMITED_TRAINS:
				unlimitedTrains = packet.readBoolean();
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	public void setTrainMapping(String customId, TrainType trainType, Consumer<PacketByteBuf> sendPacket) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeLong(id);
		packet.writeString(KEY_TRAIN_TYPE);
		packet.writeString(customId);
		packet.writeInt(trainType.ordinal());
		sendPacket.accept(packet);
		setTrainDetails(customId, trainType);
	}

	public void setUnlimitedTrains(boolean unlimitedTrains, Consumer<PacketByteBuf> sendPacket) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeLong(id);
		packet.writeString(KEY_UNLIMITED_TRAINS);
		packet.writeBoolean(unlimitedTrains);
		sendPacket.accept(packet);
		this.unlimitedTrains = unlimitedTrains;
	}

	public CustomResources.TrainMapping getTrainMapping() {
		return trainMapping;
	}

	public void setSidingData(World world, Depot depot, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		this.world = world;
		this.depot = depot;

		if (depot == null) {
			trains.clear();
			path.clear();
			distances.clear();
		} else if (path.isEmpty()) {
			generateDefaultPath(rails);
			generateDistancesAndTimePoints();
		}
	}

	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, SavedRailBase firstPlatform, SavedRailBase lastPlatform) {
		final List<PathData> tempPath = new ArrayList<>();
		final int successfulSegments;
		if (firstPlatform == null || lastPlatform == null) {
			successfulSegments = 0;
		} else {
			final List<SavedRailBase> depotAndFirstPlatform = new ArrayList<>();
			depotAndFirstPlatform.add(this);
			depotAndFirstPlatform.add(firstPlatform);
			PathFinder.findPath(tempPath, rails, depotAndFirstPlatform, 0);

			if (tempPath.isEmpty()) {
				successfulSegments = 1;
			} else if (mainPath.isEmpty()) {
				tempPath.clear();
				successfulSegments = successfulSegmentsMain + 1;
			} else {
				PathFinder.appendPath(tempPath, mainPath);

				final List<SavedRailBase> lastPlatformAndDepot = new ArrayList<>();
				lastPlatformAndDepot.add(lastPlatform);
				lastPlatformAndDepot.add(this);
				final List<PathData> pathLastPlatformToDepot = new ArrayList<>();
				PathFinder.findPath(pathLastPlatformToDepot, rails, lastPlatformAndDepot, successfulSegmentsMain);

				if (pathLastPlatformToDepot.isEmpty()) {
					successfulSegments = successfulSegmentsMain + 1;
					tempPath.clear();
				} else {
					PathFinder.appendPath(tempPath, pathLastPlatformToDepot);
					successfulSegments = successfulSegmentsMain + 2;
				}
			}
		}

		minecraftServer.execute(() -> {
			try {
				path.clear();
				if (tempPath.isEmpty()) {
					generateDefaultPath(rails);
				} else {
					path.addAll(tempPath);
				}
				generateDistancesAndTimePoints();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return successfulSegments;
	}

	public void simulateTrain(float ticksElapsed, DataCache dataCache, List<Set<UUID>> trainPositions, Map<PlayerEntity, Set<TrainServer>> trainsInPlayerRange, Set<TrainServer> trainsToSync, Map<Long, Set<Route.ScheduleEntry>> schedulesForPlatform) {
		if (depot == null) {
			return;
		}

		int trainsAtDepot = 0;
		boolean spawnTrain = true;

		final Set<Integer> railProgressSet = new HashSet<>();
		final Set<TrainServer> trainsToRemove = new HashSet<>();
		for (final TrainServer train : trains) {
			if (train.simulateTrain(world, ticksElapsed, depot, dataCache, trainPositions == null ? null : trainPositions.get(0), trainsInPlayerRange, schedulesForPlatform, unlimitedTrains)) {
				trainsToSync.add(train);
			}

			if (train.closeToDepot(trainMapping.trainType.getSpacing() * trainLength)) {
				spawnTrain = false;
			}

			if (!train.getIsOnRoute()) {
				trainsAtDepot++;
				if (trainsAtDepot > 1) {
					trainsToRemove.add(train);
				}
			}

			final int roundedRailProgress = Math.round(train.getRailProgress() * 10);
			if (railProgressSet.contains(roundedRailProgress)) {
				trainsToRemove.add(train);
			}
			railProgressSet.add(roundedRailProgress);

			if (trainPositions != null) {
				train.writeTrainPositions(trainPositions.get(1));
			}
		}

		if (trains.isEmpty() || unlimitedTrains && spawnTrain) {
			final TrainServer train = new TrainServer(unlimitedTrains ? new Random().nextLong() : id, id, railLength, trainMapping, trainLength, path, distances, timePoints);
			trains.add(train);
		}

		if (!trainsToRemove.isEmpty()) {
			trainsToRemove.forEach(trains::remove);
		}
	}

	public boolean getUnlimitedTrains() {
		return unlimitedTrains;
	}

	public void clearTrains() {
		trains.clear();
	}

	private void setTrainDetails(String customId, TrainType trainType) {
		trainMapping = new CustomResources.TrainMapping(customId, trainType);
		trainLength = (int) Math.floor(railLength / trainMapping.trainType.getSpacing());
	}

	private void generateDefaultPath(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		trains.clear();

		final List<BlockPos> orderedPositions = getOrderedPositions(new BlockPos(0, 0, 0), false);
		final BlockPos pos1 = orderedPositions.get(0);
		final BlockPos pos2 = orderedPositions.get(1);
		if (RailwayData.containsRail(rails, pos1, pos2)) {
			path.add(new PathData(rails.get(pos1).get(pos2), id, 0, pos1, pos2, -1));
		}

		trains.add(new TrainServer(id, id, railLength, trainMapping, trainLength, path, distances, timePoints));
	}

	private void generateDistancesAndTimePoints() {
		distances.clear();
		timePoints.clear();

		float distanceSum = 0;
		float timeSum = 0;
		float speed = Train.ACCELERATION;
		for (final PathData pathData : path) {
			final float railLength = (float) pathData.rail.getLength();
			distanceSum += railLength;
			distances.add(distanceSum);

			final RailType railType = pathData.rail.railType;
			if (speed == Train.ACCELERATION && !railType.canAccelerate) {
				timePoints.add(timeSum);
				continue;
			}

			float distanceTracker = 0;
			while (distanceTracker < railLength) {
				final float remainingDistance = railLength - distanceTracker;
				final float railSpeed = railType.canAccelerate ? railType.maxBlocksPerTick : speed;
				if (speed == railSpeed) {
					timeSum += remainingDistance / speed;
					distanceTracker = railLength;
				} else {
					final float accelerationDistance = Math.min(Math.abs(railSpeed * railSpeed - speed * speed) / (2 * Train.ACCELERATION), remainingDistance);
					final float newSpeed = (float) Math.sqrt((speed < railSpeed ? 2 : -2) * Train.ACCELERATION * accelerationDistance + speed * speed);
					timeSum += 2 * accelerationDistance / (speed + newSpeed);
					speed = newSpeed;
					distanceTracker += accelerationDistance;
				}
			}

			if (pathData.dwellTime > 0) {
				timeSum += speed / (2 * Train.ACCELERATION);
				timePoints.add(timeSum);
				timeSum += pathData.dwellTime * 10;
				speed = Train.ACCELERATION;
			} else {
				timePoints.add(timeSum);
			}
		}

		if (path.size() != 1) {
			trains.removeIf(train -> (train.id == id) == unlimitedTrains);
		}
	}
}
