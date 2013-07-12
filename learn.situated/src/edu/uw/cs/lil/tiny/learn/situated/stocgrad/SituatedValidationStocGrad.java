/*******************************************************************************
 * UW SPF - The University of Washington Semantic Parsing Framework
 * <p>
 * Copyright (C) 2013 Yoav Artzi
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 ******************************************************************************/
package edu.uw.cs.lil.tiny.learn.situated.stocgrad;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.uw.cs.lil.tiny.data.IDataItem;
import edu.uw.cs.lil.tiny.data.collection.IDataCollection;
import edu.uw.cs.lil.tiny.data.lexicalgen.ILexGenDataItem;
import edu.uw.cs.lil.tiny.data.sentence.Sentence;
import edu.uw.cs.lil.tiny.data.utils.IValidator;
import edu.uw.cs.lil.tiny.learn.situated.AbstractSituatedLearner;
import edu.uw.cs.lil.tiny.parser.joint.IJointOutput;
import edu.uw.cs.lil.tiny.parser.joint.IJointOutputLogger;
import edu.uw.cs.lil.tiny.parser.joint.IJointParse;
import edu.uw.cs.lil.tiny.parser.joint.graph.IJointGraphParser;
import edu.uw.cs.lil.tiny.parser.joint.graph.IJointGraphParserOutput;
import edu.uw.cs.lil.tiny.parser.joint.model.IJointDataItemModel;
import edu.uw.cs.lil.tiny.parser.joint.model.JointModel;
import edu.uw.cs.lil.tiny.utils.hashvector.HashVectorFactory;
import edu.uw.cs.lil.tiny.utils.hashvector.IHashVector;
import edu.uw.cs.utils.composites.Pair;
import edu.uw.cs.utils.filter.IFilter;
import edu.uw.cs.utils.log.ILogger;
import edu.uw.cs.utils.log.LoggerFactory;

/**
 * Situated validation-based stochastic gradient learner.
 * 
 * @author Yoav Artzi
 * @param <STATE>
 * @param <MR>
 * @param <ESTEP>
 * @param <ERESULT>
 */
public class SituatedValidationStocGrad<STATE, MR, ESTEP, ERESULT> extends
		AbstractSituatedLearner<STATE, MR, ESTEP, ERESULT> {
	private static final ILogger											LOG						= LoggerFactory
																											.create(SituatedValidationStocGrad.class);
	
	private final double													alpha0;
	
	private final double													c;
	
	private final IJointGraphParser<Sentence, STATE, MR, ESTEP, ERESULT>	graphParser;
	
	private int																stocGradientNumUpdates	= 0;
	
	private final IValidator<IDataItem<Pair<Sentence, STATE>>, ERESULT>		validator;
	
	private SituatedValidationStocGrad(
			int numIterations,
			IDataCollection<? extends ILexGenDataItem<Pair<Sentence, STATE>, MR>> trainingData,
			Map<IDataItem<Pair<Sentence, STATE>>, Pair<MR, ERESULT>> trainingDataDebug,
			int maxSentenceLength, int lexiconGenerationBeamSize,
			IJointGraphParser<Sentence, STATE, MR, ESTEP, ERESULT> parser,
			boolean lexiconLearning,
			IJointOutputLogger<MR, ESTEP, ERESULT> parserOutputLogger,
			double alpha0, double c,
			IValidator<IDataItem<Pair<Sentence, STATE>>, ERESULT> validator) {
		super(numIterations, trainingData, trainingDataDebug,
				maxSentenceLength, lexiconGenerationBeamSize, parser,
				lexiconLearning, parserOutputLogger);
		this.graphParser = parser;
		this.alpha0 = alpha0;
		this.c = c;
		this.validator = validator;
	}
	
	@Override
	public void train(JointModel<Sentence, STATE, MR, ESTEP> model) {
		stocGradientNumUpdates = 0;
		super.train(model);
	}
	
	@Override
	protected void parameterUpdate(
			final ILexGenDataItem<Pair<Sentence, STATE>, MR> dataItem,
			IJointDataItemModel<MR, ESTEP> dataItemModel,
			JointModel<Sentence, STATE, MR, ESTEP> model, int itemCounter,
			int epochNumber) {
		
		// Parse with current model
		final IJointGraphParserOutput<MR, ERESULT> parserOutput = graphParser
				.parse(dataItem, dataItemModel);
		stats.recordModelParsing(parserOutput.getInferenceTime());
		parserOutputLogger.log(parserOutput, dataItemModel);
		final List<? extends IJointParse<MR, ERESULT>> modelParses = parserOutput
				.getAllParses();
		final List<? extends IJointParse<MR, ERESULT>> bestModelParses = parserOutput
				.getBestParses();
		
		if (modelParses.isEmpty()) {
			// Skip the rest of the process if no complete parses
			// available
			LOG.info("No parses for: %s", dataItem);
			LOG.info("Skipping parameter update");
			return;
		}
		
		LOG.info("Created %d model parses for training sample",
				modelParses.size());
		LOG.info("Model parsing time: %.4fsec",
				parserOutput.getInferenceTime() / 1000.0);
		
		// Record if the best is the gold standard, if such debug
		// information is available
		if (bestModelParses.size() == 1
				&& isGoldDebugCorrect(dataItem, bestModelParses.get(0)
						.getResult())) {
			stats.goldIsOptimal(itemCounter, epochNumber);
		}
		
		// TODO Solve the case of some lexical entries (in the factored case)
		// not in the model, so they don't have features. However, just adding
		// them is not enough, because the chart caches the previous features.
		// Thsi is true for other learners as well.
		
		// Create the update
		final IHashVector update = HashVectorFactory.create();
		
		// Step A: Compute the positive half of the update: conditioned on
		// getting successful validation
		final IFilter<ERESULT> filter = new IFilter<ERESULT>() {
			@Override
			public boolean isValid(ERESULT e) {
				return validate(dataItem, Pair.of((MR) null, e));
			}
		};
		final double conditionedNorm = parserOutput.norm(filter);
		if (conditionedNorm == 0.0) {
			// No positive update, skip the update
			return;
		} else {
			// Case have complete valid parses
			final IHashVector expectedFeatures = parserOutput
					.expectedFeatures(filter);
			expectedFeatures.divideBy(conditionedNorm);
			expectedFeatures.dropSmallEntries();
			LOG.info("Positive update: %s", expectedFeatures);
			expectedFeatures.addTimesInto(1.0, update);
		}
		
		// Step B: Compute the negative half of the update: expectation under
		// the current model
		final double norm = parserOutput.norm();
		if (norm != 0.0) {
			// Case have complete parses
			final IHashVector expectedFeatures = parserOutput
					.expectedFeatures();
			expectedFeatures.divideBy(norm);
			expectedFeatures.dropSmallEntries();
			LOG.info("Negative update: %s", expectedFeatures);
			expectedFeatures.addTimesInto(-1.0, update);
		} else {
			LOG.info("No negative update");
		}
		
		// Step C: Apply the update
		
		// Validate the update
		if (!model.isValidWeightVector(update)) {
			throw new IllegalStateException("invalid update: " + update);
		}
		
		// Scale the update
		final double scale = alpha0 / (1.0 + c * stocGradientNumUpdates);
		update.multiplyBy(scale);
		update.dropSmallEntries();
		stocGradientNumUpdates++;
		LOG.info("Scale: %f", scale);
		if (update.size() == 0) {
			LOG.info("No update");
		} else {
			LOG.info("Update: %s", update);
			stats.triggeredUpdate(itemCounter, epochNumber);
		}
		
		// Check for NaNs and super large updates
		if (update.isBad()) {
			LOG.error("Bad update: %s -- norm: %f.4f -- feats: %s", update,
					norm, null);
			LOG.error(model.getTheta().printValues(update));
			throw new IllegalStateException("bad update");
		} else {
			if (!update.valuesInRange(-100, 100)) {
				LOG.warn("Large update");
			}
			// Do the update
			update.addTimesInto(1, model.getTheta());
		}
	}
	
	@Override
	protected boolean validate(IDataItem<Pair<Sentence, STATE>> dataItem,
			Pair<MR, ERESULT> hypothesis) {
		return validator.isValid(dataItem, hypothesis.second());
	}
	
	public static class Builder<STATE, MR, ESTEP, ERESULT> {
		
		/**
		 * Used to define the temperature of parameter updates. temp =
		 * alpha_0/(1+c*tot_number_of_training_instances)
		 */
		private double																		alpha0						= 0.1;
		
		/**
		 * Used to define the temperature of parameter updates. temp =
		 * alpha_0/(1+c*tot_number_of_training_instances)
		 */
		private double																		c							= 0.0001;
		
		/**
		 * Beam size to use when doing loss sensitive pruning with generated
		 * lexicon.
		 */
		private int																			lexiconGenerationBeamSize	= 20;
		
		/**
		 * Learn a lexicon.
		 */
		private boolean																		lexiconLearning				= true;
		/**
		 * Max sentence length. Sentence longer than this value will be skipped
		 * during training
		 */
		private int																			maxSentenceLength			= Integer.MAX_VALUE;
		
		/** Number of training iterations */
		private int																			numIterations				= 4;
		
		private final IJointGraphParser<Sentence, STATE, MR, ESTEP, ERESULT>				parser;
		
		private IJointOutputLogger<MR, ESTEP, ERESULT>										parserOutputLogger			= new IJointOutputLogger<MR, ESTEP, ERESULT>() {
																															
																															public void log(
																																	IJointOutput<MR, ERESULT> output,
																																	IJointDataItemModel<MR, ESTEP> dataItemModel) {
																																// Stub
																																
																															}
																														};
		
		/** Training data */
		private final IDataCollection<? extends ILexGenDataItem<Pair<Sentence, STATE>, MR>>	trainingData;
		
		/**
		 * Mapping a subset of training samples into their gold label for debug.
		 */
		private Map<IDataItem<Pair<Sentence, STATE>>, Pair<MR, ERESULT>>					trainingDataDebug			= new HashMap<IDataItem<Pair<Sentence, STATE>>, Pair<MR, ERESULT>>();
		
		private final IValidator<IDataItem<Pair<Sentence, STATE>>, ERESULT>					validator;
		
		public Builder(
				IDataCollection<? extends ILexGenDataItem<Pair<Sentence, STATE>, MR>> trainingData,
				IJointGraphParser<Sentence, STATE, MR, ESTEP, ERESULT> parser,
				IValidator<IDataItem<Pair<Sentence, STATE>>, ERESULT> validator) {
			this.trainingData = trainingData;
			this.parser = parser;
			this.validator = validator;
		}
		
		public SituatedValidationStocGrad<STATE, MR, ESTEP, ERESULT> build() {
			return new SituatedValidationStocGrad<STATE, MR, ESTEP, ERESULT>(
					numIterations, trainingData, trainingDataDebug,
					maxSentenceLength, lexiconGenerationBeamSize, parser,
					lexiconLearning, parserOutputLogger, alpha0, c, validator);
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setAlpha0(double alpha0) {
			this.alpha0 = alpha0;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setC(double c) {
			this.c = c;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setLexiconGenerationBeamSize(
				int lexiconGenerationBeamSize) {
			this.lexiconGenerationBeamSize = lexiconGenerationBeamSize;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setLexiconLearning(
				boolean lexiconLearning) {
			this.lexiconLearning = lexiconLearning;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setMaxSentenceLength(
				int maxSentenceLength) {
			this.maxSentenceLength = maxSentenceLength;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setNumIterations(
				int numIterations) {
			this.numIterations = numIterations;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setParserOutputLogger(
				IJointOutputLogger<MR, ESTEP, ERESULT> parserOutputLogger) {
			this.parserOutputLogger = parserOutputLogger;
			return this;
		}
		
		public Builder<STATE, MR, ESTEP, ERESULT> setTrainingDataDebug(
				Map<IDataItem<Pair<Sentence, STATE>>, Pair<MR, ERESULT>> trainingDataDebug) {
			this.trainingDataDebug = trainingDataDebug;
			return this;
		}
		
	}
}
