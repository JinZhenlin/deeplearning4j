package org.deeplearning4j.models.paragraphvectors;

import com.google.common.collect.Lists;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.deeplearning4j.berkeley.Counter;
import org.deeplearning4j.models.embeddings.WeightLookupTable;
import org.deeplearning4j.models.embeddings.inmemory.InMemoryLookupTable;
import org.deeplearning4j.models.embeddings.learning.ElementsLearningAlgorithm;
import org.deeplearning4j.models.embeddings.learning.SequenceLearningAlgorithm;
import org.deeplearning4j.models.embeddings.learning.impl.sequence.DM;
import org.deeplearning4j.models.embeddings.loader.VectorsConfiguration;
import org.deeplearning4j.models.embeddings.reader.ModelUtils;
import org.deeplearning4j.models.embeddings.reader.impl.BasicModelUtils;
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors;
import org.deeplearning4j.models.sequencevectors.interfaces.SequenceIterator;
import org.deeplearning4j.models.sequencevectors.interfaces.VectorsListener;
import org.deeplearning4j.models.sequencevectors.iterators.AbstractSequenceIterator;
import org.deeplearning4j.models.sequencevectors.sequence.Sequence;
import org.deeplearning4j.models.sequencevectors.transformers.impl.SentenceTransformer;
import org.deeplearning4j.models.word2vec.VocabWord;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.deeplearning4j.models.word2vec.wordstore.VocabCache;
import org.deeplearning4j.text.documentiterator.*;
import org.deeplearning4j.text.documentiterator.interoperability.DocumentIteratorConverter;
import org.deeplearning4j.text.invertedindex.InvertedIndex;
import org.deeplearning4j.text.sentenceiterator.SentenceIterator;
import org.deeplearning4j.text.sentenceiterator.interoperability.SentenceIteratorConverter;
import org.deeplearning4j.text.sentenceiterator.labelaware.LabelAwareSentenceIterator;
import org.deeplearning4j.text.tokenization.tokenizerfactory.TokenizerFactory;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.util.*;

/**
 * Basic ParagraphVectors (aka Doc2Vec) implementation for DL4j, as wrapper over SequenceVectors
 *
 * @author raver119@gmail.com
 */
public class ParagraphVectors extends Word2Vec {
    @Getter protected LabelsSource labelsSource;
    @Getter @Setter protected transient LabelAwareIterator labelAwareIterator;
    protected INDArray labelsMatrix;
    protected List<VocabWord> labelsList = new ArrayList<>();
    protected boolean normalizedLabels = false;

    /**
     * This method takes raw text, applies tokenizer, and returns most probable label
     *
     * @param rawText
     * @return
     */
    @Deprecated
    public String predict(String rawText) {
        if (tokenizerFactory == null) throw new IllegalStateException("TokenizerFactory should be defined, prior to predict() call");

        List<String> tokens = tokenizerFactory.create(rawText).getTokens();
        List<VocabWord> document = new ArrayList<>();
        for (String token: tokens) {
            if (vocab.containsWord(token)) {
                document.add(vocab.wordFor(token));
            }
        }

        return predict(document);
    }

    /**
     * This method predicts label of the document.
     * Computes a similarity wrt the mean of the
     * representation of words in the document
     * @param document the document
     * @return the word distances for each label
     */
    @Deprecated
    public String predict(LabelledDocument document) {
        if (document.getReferencedContent() != null)
            return predict(document.getReferencedContent());
        else return predict(document.getContent());
    }

    public void extractLabels(){
        Collection<VocabWord> vocabWordCollection = vocab.vocabWords();
        List<VocabWord> vocabWordList = new ArrayList<>();
        int[] indexArray;

        //INDArray pulledArray;
        //Check if word has label and build a list out of the collection
        for(VocabWord vWord:vocabWordCollection){
            if (vWord.isLabel()){
                vocabWordList.add(vWord);
            }
        }
        //Build array of indexes in the order of the vocablist
        indexArray = new int[vocabWordList.size()];
        int i = 0;
        for (VocabWord vWord:vocabWordList){
            indexArray[i] = vWord.getIndex();
            i++;
        }
        //pull the label rows and create new matrix
        if (i > 0) {
            labelsMatrix = Nd4j.pullRows(lookupTable.getWeights(), 1, indexArray);
            labelsList = vocabWordList;
        }
    }

    /**
     * This method calculates inferred vector for given text
     *
     * @param text
     * @return
     */
    public INDArray inferVector(String text, double learningRate, double minLearningRate, int iterations) {
        if (tokenizerFactory == null) throw new IllegalStateException("TokenizerFactory should be defined, prior to predict() call");

        List<String> tokens = tokenizerFactory.create(text).getTokens();
        List<VocabWord> document = new ArrayList<>();
        for (String token: tokens) {
            if (vocab.containsWord(token)) {
                document.add(vocab.wordFor(token));
            }
        }

        if (document.isEmpty())
            throw new ND4JIllegalStateException("Text passed for inference has no matches in model vocabulary.");

        return inferVector(document, learningRate, minLearningRate, iterations);
    }

    /**
     * This method calculates inferred vector for given document
     *
     * @param document
     * @return
     */
    public INDArray inferVector(LabelledDocument document, double learningRate, double minLearningRate, int iterations) {
        if (document.getReferencedContent() != null && !document.getReferencedContent().isEmpty()) {
            return inferVector(document.getReferencedContent(), learningRate, minLearningRate, iterations);
        } else return inferVector(document.getContent(), learningRate, minLearningRate, iterations);
    }

    /**
     * This method calculates inferred vector for given document
     *
     * @param document
     * @return
     */
    public INDArray inferVector(@NonNull List<VocabWord> document, double learningRate, double minLearningRate, int iterations) {
        SequenceLearningAlgorithm<VocabWord> learner = sequenceLearningAlgorithm;

        if (learner == null) {
            synchronized (this) {
                if (learner == null) {
                    log.info("Creating new PV-DM learner...");
                    learner = new DM<VocabWord>();
                    learner.configure(vocab, lookupTable, configuration);
                }
            }
        }

        if (document.isEmpty())
            throw new ND4JIllegalStateException("Impossible to apply inference to empty list of words");


        Sequence<VocabWord> sequence = new Sequence<>();
        sequence.addElements(document);
        sequence.setSequenceLabel(new VocabWord(1.0, String.valueOf(new Random().nextInt())));

        initLearners();

        INDArray inf = learner.inferSequence(sequence, 119, learningRate, minLearningRate, iterations);

        return inf;
    }

    /**
     * This method calculates inferred vector for given text, with default parameters for learning rate and iterations
     *
     * @param text
     * @return
     */
    public INDArray inferVector(String text) {
        return inferVector(text, this.learningRate.get(), this.minLearningRate, this.numEpochs * this.numIterations);
    }

    /**
     * This method calculates inferred vector for given document, with default parameters for learning rate and iterations
     *
     * @param document
     * @return
     */
    public INDArray inferVector(LabelledDocument document) {
        return inferVector(document, this.learningRate.get(), this.minLearningRate, this.numEpochs * this.numIterations);
    }

    /**
     * This method calculates inferred vector for given list of words, with default parameters for learning rate and iterations
     *
     * @param document
     * @return
     */
    public INDArray inferVector(@NonNull List<VocabWord> document) {
        return inferVector(document, this.learningRate.get(), this.minLearningRate, this.numEpochs * this.numIterations);
    }

    /**
     * This method predicts label of the document.
     * Computes a similarity wrt the mean of the
     * representation of words in the document
     * @param document the document
     * @return the word distances for each label
     */
    @Deprecated
    public String predict(List<VocabWord> document) {
        /*
            This code was transferred from original ParagraphVectors DL4j implementation, and yet to be tested
         */
        if (document.isEmpty()) throw new IllegalStateException("Document has no words inside");

        INDArray arr = Nd4j.create(document.size(),this.layerSize);
        for(int i = 0; i < document.size(); i++) {
            arr.putRow(i,getWordVectorMatrix(document.get(i).getWord()));
        }

        INDArray docMean = arr.mean(0);
        Counter<String> distances = new Counter<>();

        for(String s : labelsSource.getLabels()) {
            INDArray otherVec = getWordVectorMatrix(s);
            double sim = Transforms.cosineSim(docMean, otherVec);
            distances.incrementCount(s, sim);
        }

        return distances.argMax();
    }

    /**
     * Predict several labels based on the document.
     * Computes a similarity wrt the mean of the
     * representation of words in the document
     * @param document raw text of the document
     * @return possible labels in descending order
     */
    @Deprecated
    public Collection<String> predictSeveral(@NonNull LabelledDocument document, int limit) {
        if (document.getReferencedContent() != null) {
            return predictSeveral(document.getReferencedContent(), limit);
        } else return predictSeveral(document.getContent(), limit);
    }

    /**
     * Predict several labels based on the document.
     * Computes a similarity wrt the mean of the
     * representation of words in the document
     * @param rawText raw text of the document
     * @return possible labels in descending order
     */
    @Deprecated
    public Collection<String> predictSeveral(String rawText, int limit) {
        if (tokenizerFactory == null) throw new IllegalStateException("TokenizerFactory should be defined, prior to predict() call");

        List<String> tokens = tokenizerFactory.create(rawText).getTokens();
        List<VocabWord> document = new ArrayList<>();
        for (String token: tokens) {
            if (vocab.containsWord(token)) {
                document.add(vocab.wordFor(token));
            }
        }

        return predictSeveral(document, limit);
    }

    /**
     * Predict several labels based on the document.
     * Computes a similarity wrt the mean of the
     * representation of words in the document
     * @param document the document
     * @return possible labels in descending order
     */
    @Deprecated
    public Collection<String> predictSeveral(List<VocabWord> document, int limit) {
        /*
            This code was transferred from original ParagraphVectors DL4j implementation, and yet to be tested
         */
        if (document.isEmpty()) throw new IllegalStateException("Document has no words inside");

        INDArray arr = Nd4j.create(document.size(),this.layerSize);
        for(int i = 0; i < document.size(); i++) {
            arr.putRow(i,getWordVectorMatrix(document.get(i).getWord()));
        }

        INDArray docMean = arr.mean(0);
        Counter<String> distances = new Counter<>();

        for(String s : labelsSource.getLabels()) {
            INDArray otherVec = getWordVectorMatrix(s);
            double sim = Transforms.cosineSim(docMean, otherVec);
            log.debug("Similarity inside: ["+s+"] -> " + sim);
            distances.incrementCount(s, sim);
        }

        return distances.getSortedKeys().subList(0, limit);
    }

    /**
     * This method returns top N labels nearest to specified document
     *
     * @param document
     * @param topN
     * @return
     */
    public Collection<String> nearestLabels(LabelledDocument document, int topN) {
        if (document.getReferencedContent() != null) {
            return nearestLabels(document.getReferencedContent(), topN);
        } else return nearestLabels(document.getContent(), topN);
    }

    /**
     * This method returns top N labels nearest to specified text
     *
     * @param rawText
     * @param topN
     * @return
     */
    public Collection<String> nearestLabels(@NonNull String rawText, int topN) {
        List<String> tokens = tokenizerFactory.create(rawText).getTokens();
        List<VocabWord> document = new ArrayList<>();
        for (String token: tokens) {
            if (vocab.containsWord(token)) {
                document.add(vocab.wordFor(token));
            }
        }

        // we're returning empty collection for empty document
        if (document.isEmpty()) {
            log.info("Document passed to nearestLabels() has no matches in model vocabulary");
            return new ArrayList<>();
        }

        return  nearestLabels(document, topN);
    }

    /**
     * This method returns top N labels nearest to specified set of vocab words
     *
     * @param document
     * @param topN
     * @return
     */
    public Collection<String> nearestLabels(@NonNull Collection<VocabWord> document, int topN) {
        if (document.isEmpty())
            throw new ND4JIllegalStateException("Impossible to get nearestLabels for empty list of words");

        INDArray vector = inferVector(new ArrayList<VocabWord>(document));
        return nearestLabels(vector, topN);
    }

    /**
     * This method returns top N labels nearest to specified features vector
     *
     * @param labelVector
     * @param topN
     * @return
     */
    public Collection<String> nearestLabels(INDArray labelVector, int topN) {
        if (labelsMatrix == null || labelsList == null || labelsList.isEmpty())
            extractLabels();

        List<BasicModelUtils.WordSimilarity> result = new ArrayList<>();

        // if list still empty - return empty collection
        if (labelsMatrix == null || labelsList == null || labelsList.isEmpty()) {
            log.warn("Labels list is empty!");
            return new ArrayList<>();
        }

        if (!normalizedLabels) {
            synchronized (this) {
                if (!normalizedLabels) {
                    labelsMatrix.diviColumnVector(labelsMatrix.norm1(1));
                    normalizedLabels = true;
                }
            }
        }

        INDArray similarity = Transforms.unitVec(labelVector).mmul(labelsMatrix.transpose());
        List<Double> highToLowSimList = getTopN(similarity, topN + 20);

        for (int i = 0; i < highToLowSimList.size(); i++) {
            String word = labelsList.get(highToLowSimList.get(i).intValue()).getLabel();
            if (word != null && !word.equals("UNK") && !word.equals("STOP") ) {
                INDArray otherVec = lookupTable.vector(word);
                double sim = Transforms.cosineSim(labelVector, otherVec);

                result.add(new BasicModelUtils.WordSimilarity(word, sim));
            }
        }

        Collections.sort(result, new BasicModelUtils.SimilarityComparator());

        return BasicModelUtils.getLabels(result, topN);
    }

    /**
     * Get top N elements
     *
     * @param vec the vec to extract the top elements from
     * @param N the number of elements to extract
     * @return the indices and the sorted top N elements
     */
    private List<Double> getTopN(INDArray vec, int N) {
        BasicModelUtils.ArrayComparator comparator = new BasicModelUtils.ArrayComparator();
        PriorityQueue<Double[]> queue = new PriorityQueue<>(vec.rows(),comparator);

        for (int j = 0; j < vec.length(); j++) {
            final Double[] pair = new Double[]{vec.getDouble(j), (double) j};
            if (queue.size() < N) {
                queue.add(pair);
            } else {
                Double[] head = queue.peek();
                if (comparator.compare(pair, head) > 0) {
                    queue.poll();
                    queue.add(pair);
                }
            }
        }

        List<Double> lowToHighSimLst = new ArrayList<>();

        while (!queue.isEmpty()) {
            double ind = queue.poll()[1];
            lowToHighSimLst.add(ind);
        }
        return Lists.reverse(lowToHighSimLst);
    }

    /**
     * This method returns similarity of the document to specific label, based on mean value
     *
     * @param rawText
     * @param label
     * @return
     */
    @Deprecated
    public double similarityToLabel(String rawText, String label) {
        if (tokenizerFactory == null) throw new IllegalStateException("TokenizerFactory should be defined, prior to predict() call");

        List<String> tokens = tokenizerFactory.create(rawText).getTokens();
        List<VocabWord> document = new ArrayList<>();
        for (String token: tokens) {
            if (vocab.containsWord(token)) {
                document.add(vocab.wordFor(token));
            }
        }
        return similarityToLabel(document, label);
    }

    @Override
    public void fit() {
        super.fit();

        extractLabels();
    }

    /**
     * This method returns similarity of the document to specific label, based on mean value
     *
     * @param document
     * @param label
     * @return
     */
    @Deprecated
    public double similarityToLabel(LabelledDocument document, String label) {
        if (document.getReferencedContent() != null) {
            return similarityToLabel(document.getReferencedContent(), label);
        } else return similarityToLabel(document.getContent(), label);
    }

    /**
     * This method returns similarity of the document to specific label, based on mean value
     *
     * @param document
     * @param label
     * @return
     */
    @Deprecated
    public double similarityToLabel(List<VocabWord> document, String label) {
        if (document.isEmpty()) throw new IllegalStateException("Document has no words inside");

        INDArray arr = Nd4j.create(document.size(),this.layerSize);
        for(int i = 0; i < document.size(); i++) {
            arr.putRow(i,getWordVectorMatrix(document.get(i).getWord()));
        }

        INDArray docMean = arr.mean(0);

        INDArray otherVec = getWordVectorMatrix(label);
        double sim = Transforms.cosineSim(docMean, otherVec);
        return sim;
    }



    public static class Builder extends Word2Vec.Builder {
        protected LabelAwareIterator labelAwareIterator;
        protected LabelsSource labelsSource;
        protected DocumentIterator docIter;




        /**
         * This method allows you to use pre-built WordVectors model (Word2Vec or GloVe) for ParagraphVectors.
         * Existing model will be transferred into new model before training starts.
         *
         * PLEASE NOTE: Non-normalized model is recommended to use here.
         *
         * @param vec existing WordVectors model
         * @return
         */
        @Override
        @SuppressWarnings("unchecked")
        public Builder useExistingWordVectors(@NonNull WordVectors vec) {
            if (((InMemoryLookupTable<VocabWord>)vec.lookupTable()).getSyn1() == null &&
                    ((InMemoryLookupTable<VocabWord>)vec.lookupTable()).getSyn1Neg() == null)
                throw new ND4JIllegalStateException("Model being passed as existing has no syn1/syn1Neg available");

            this.existingVectors = vec;
            return this;
        }

        /**
         * This method defines, if words representations should be build together with documents representations.
         *
         * @param trainElements
         * @return
         */
        public Builder trainWordVectors(boolean trainElements) {
            this.trainElementsRepresentation(trainElements);
            return this;
        }

        /**
         * This method attaches pre-defined labels source to ParagraphVectors
         *
         * @param source
         * @return
         */
        public Builder labelsSource(@NonNull LabelsSource source) {
            this.labelsSource = source;
            return this;
        }

        /**
         * This method builds new LabelSource instance from labels.
         *
         * PLEASE NOTE: Order synchro between labels and input documents delegated to end-user.
         * PLEASE NOTE: Due to order issues it's recommended to use label aware iterators instead.
         *
         * @param labels
         * @return
         */
        @Deprecated
        public Builder labels(@NonNull List<String> labels) {
            this.labelsSource = new LabelsSource(labels);
            return this;
        }

        /**
         * This method used to feed LabelAwareDocumentIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        public Builder iterate(@NonNull LabelAwareDocumentIterator iterator) {
            this.docIter = iterator;
            return this;
        }

        /**
         * This method used to feed LabelAwareSentenceIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        public Builder iterate(@NonNull LabelAwareSentenceIterator iterator) {
            this.sentenceIterator = iterator;
            return this;
        }

        /**
         * This method used to feed LabelAwareIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        public Builder iterate(@NonNull LabelAwareIterator iterator) {
            this.labelAwareIterator = iterator;
            return this;
        }

        /**
         * This method used to feed DocumentIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        @Override
        public Builder iterate(@NonNull DocumentIterator iterator) {
            this.docIter = iterator;
            return this;
        }

        /**
         * This method used to feed SentenceIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        @Override
        public Builder iterate(@NonNull SentenceIterator iterator) {
            this.sentenceIterator = iterator;
            return this;
        }

        /**
         * Sets ModelUtils that gonna be used as provider for utility methods: similarity(), wordsNearest(), accuracy(), etc
         *
         * @param modelUtils model utils to be used
         * @return
         */
        @Override
        public Builder modelUtils(@NonNull ModelUtils<VocabWord> modelUtils) {
            super.modelUtils(modelUtils);
            return this;
        }

        /**
         * This method allows you to specify SequenceElement that will be used as UNK element, if UNK is used
         *
         * @param element
         * @return
         */
        @Override
        public Builder unknownElement(VocabWord element) {
            super.unknownElement(element);
            return this;
        }

        /**
         * This method enables/disables parallel tokenization.
         *
         * Default value: TRUE
         * @param allow
         * @return
         */
        @Override
        public Builder allowParallelTokenization(boolean allow) {
            super.allowParallelTokenization(allow);
            return this;
        }

        /**
         * This method allows you to specify, if UNK word should be used internally
         *
         * @param reallyUse
         * @return
         */
        @Override
        public Builder useUnknown(boolean reallyUse) {
            super.useUnknown(reallyUse);
            if (this.unknownElement == null) {
                this.unknownElement(new VocabWord(1.0, ParagraphVectors.DEFAULT_UNK));
            }
            return this;
        }

        /**
         * This method ebables/disables periodical vocab truncation during construction
         *
         * Default value: disabled
         *
         * @param reallyEnable
         * @return
         */
        @Override
        public Builder enableScavenger(boolean reallyEnable) {
            super.enableScavenger(reallyEnable);
            return this;
        }

        @Override
        public ParagraphVectors build() {
            presetTables();

            ParagraphVectors ret = new ParagraphVectors();

            if (this.existingVectors != null) {
                this.trainElementsVectors = false;
                this.elementsLearningAlgorithm = null;

                //this.lookupTable = this.existingVectors.lookupTable();
                //this.vocabCache = this.existingVectors.vocab();
            }

            if (this.labelsSource == null) this.labelsSource = new LabelsSource();
            if (docIter != null) {
                /*
                        we're going to work with DocumentIterator.
                        First, we have to assume that user can provide LabelAwareIterator. In this case we'll use them, as provided source, and collec labels provided there
                        Otherwise we'll go for own labels via LabelsSource
                */

                if (docIter instanceof LabelAwareDocumentIterator) this.labelAwareIterator = new DocumentIteratorConverter((LabelAwareDocumentIterator) docIter, labelsSource);
                    else this.labelAwareIterator = new DocumentIteratorConverter(docIter, labelsSource);
            } else if (sentenceIterator != null) {
                    // we have SentenceIterator. Mechanics will be the same, as above
                 if (sentenceIterator instanceof LabelAwareSentenceIterator) this.labelAwareIterator = new SentenceIteratorConverter((LabelAwareSentenceIterator) sentenceIterator, labelsSource);
                      else this.labelAwareIterator = new SentenceIteratorConverter(sentenceIterator, labelsSource);
            } else if (labelAwareIterator != null) {
                 // if we have LabelAwareIterator defined, we have to be sure that LabelsSource is propagated properly
                 this.labelsSource = labelAwareIterator.getLabelsSource();
            } else  {
                // we have nothing, probably that's restored model building. ignore iterator for now.
                // probably there's few reasons to move iterator initialization code into ParagraphVectors methods. Like protected setLabelAwareIterator method.
            }

            if (labelAwareIterator != null) {
                SentenceTransformer transformer = new SentenceTransformer.Builder()
                        .iterator(labelAwareIterator)
                        .tokenizerFactory(tokenizerFactory)
                        .allowMultithreading(allowParallelTokenization)
                        .build();
                this.iterator = new AbstractSequenceIterator.Builder<>(transformer).build();
            }

            ret.numEpochs = this.numEpochs;
            ret.numIterations = this.iterations;
            ret.vocab = this.vocabCache;
            ret.minWordFrequency = this.minWordFrequency;
            ret.learningRate.set(this.learningRate);
            ret.minLearningRate = this.minLearningRate;
            ret.sampling = this.sampling;
            ret.negative = this.negative;
            ret.layerSize = this.layerSize;
            ret.batchSize = this.batchSize;
            ret.learningRateDecayWords = this.learningRateDecayWords;
            ret.window = this.window;
            ret.resetModel = this.resetModel;
            ret.useAdeGrad = this.useAdaGrad;
            ret.stopWords = this.stopWords;
            ret.workers = this.workers;
            ret.useUnknown = this.useUnknown;
            ret.unknownElement = this.unknownElement;
            ret.seed = this.seed;
            ret.enableScavenger = this.enableScavenger;

            ret.trainElementsVectors = this.trainElementsVectors;
            ret.trainSequenceVectors = this.trainSequenceVectors;

            ret.elementsLearningAlgorithm = this.elementsLearningAlgorithm;
            ret.sequenceLearningAlgorithm = this.sequenceLearningAlgorithm;

            ret.tokenizerFactory = this.tokenizerFactory;

            ret.existingModel = this.existingVectors;

            ret.lookupTable = this.lookupTable;
            ret.modelUtils = this.modelUtils;
            ret.eventListeners = this.vectorsListeners;

            this.configuration.setLearningRate(this.learningRate);
            this.configuration.setLayersSize(layerSize);
            this.configuration.setHugeModelExpected(hugeModelExpected);
            this.configuration.setWindow(window);
            this.configuration.setMinWordFrequency(minWordFrequency);
            this.configuration.setIterations(iterations);
            this.configuration.setSeed(seed);
            this.configuration.setBatchSize(batchSize);
            this.configuration.setLearningRateDecayWords(learningRateDecayWords);
            this.configuration.setMinLearningRate(minLearningRate);
            this.configuration.setSampling(this.sampling);
            this.configuration.setUseAdaGrad(useAdaGrad);
            this.configuration.setNegative(negative);
            this.configuration.setEpochs(this.numEpochs);
            this.configuration.setStopList(this.stopWords);
            this.configuration.setUseHierarchicSoftmax(this.useHierarchicSoftmax);
            this.configuration.setTrainElementsVectors(this.trainElementsVectors);
            this.configuration.setPreciseWeightInit(this.preciseWeightInit);
            this.configuration.setSequenceLearningAlgorithm(this.sequenceLearningAlgorithm.getClass().getCanonicalName());
            this.configuration.setModelUtils(this.modelUtils.getClass().getCanonicalName());
            this.configuration.setAllowParallelTokenization(this.allowParallelTokenization);

            if (tokenizerFactory != null) {
                this.configuration.setTokenizerFactory(tokenizerFactory.getClass().getCanonicalName());
                if (tokenizerFactory.getTokenPreProcessor() != null)
                    this.configuration.setTokenPreProcessor(tokenizerFactory.getTokenPreProcessor().getClass().getCanonicalName());
            }

            ret.configuration = this.configuration;


            // hardcoded to TRUE, since it's ParagraphVectors wrapper
            ret.trainElementsVectors = this.trainElementsVectors;
            ret.trainSequenceVectors = true;
            ret.labelsSource = this.labelsSource;
            ret.labelAwareIterator = this.labelAwareIterator;
            ret.iterator = this.iterator;

            return ret;
        }

        public Builder() {
            super();
        }

        public Builder(@NonNull VectorsConfiguration configuration) {
            super(configuration);
        }


        /**
         * This method defines TokenizerFactory to be used for strings tokenization during training
         * PLEASE NOTE: If external VocabCache is used, the same TokenizerFactory should be used to keep derived tokens equal.
         *
         * @param tokenizerFactory
         * @return
         */
        @Override
        public Builder tokenizerFactory(@NonNull TokenizerFactory tokenizerFactory) {
            super.tokenizerFactory(tokenizerFactory);
            return this;
        }

        @Override
        public Builder index(@NonNull InvertedIndex<VocabWord> index) {
            super.index(index);
            return this;
        }

        /**
         * This method used to feed SequenceIterator, that contains training corpus, into ParagraphVectors
         *
         * @param iterator
         * @return
         */
        @Override
        public Builder iterate(@NonNull SequenceIterator<VocabWord> iterator) {
            super.iterate(iterator);
            return this;
        }

        /**
         * This method defines mini-batch size
         * @param batchSize
         * @return
         */
        @Override
        public Builder batchSize(int batchSize) {
            super.batchSize(batchSize);
            return this;
        }

        /**
         * This method defines number of iterations done for each mini-batch during training
         * @param iterations
         * @return
         */
        @Override
        public Builder iterations(int iterations) {
            super.iterations(iterations);
            return this;
        }

        /**
         * This method defines number of epochs (iterations over whole training corpus) for training
         * @param numEpochs
         * @return
         */
        @Override
        public Builder epochs(int numEpochs) {
            super.epochs(numEpochs);
            return this;
        }

        /**
         * This method defines number of dimensions for output vectors
         * @param layerSize
         * @return
         */
        @Override
        public Builder layerSize(int layerSize) {
            super.layerSize(layerSize);
            return this;
        }

        /**
         * This method sets VectorsListeners for this SequenceVectors model
         *
         * @param vectorsListeners
         * @return
         */
        @Override
        public Builder setVectorsListeners(@NonNull Collection<VectorsListener<VocabWord>> vectorsListeners) {
            super.setVectorsListeners(vectorsListeners);
            return this;
        }

        /**
         * This method defines initial learning rate for model training
         *
         * @param learningRate
         * @return
         */
        @Override
        public Builder learningRate(double learningRate) {
            super.learningRate(learningRate);
            return this;
        }

        /**
         * This method defines minimal word frequency in training corpus. All words below this threshold will be removed prior model training
         *
         * @param minWordFrequency
         * @return
         */
        @Override
        public Builder minWordFrequency(int minWordFrequency) {
            super.minWordFrequency(minWordFrequency);
            return this;
        }

        /**
         * This method defines minimal learning rate value for training
         *
         * @param minLearningRate
         * @return
         */
        @Override
        public Builder minLearningRate(double minLearningRate) {
            super.minLearningRate(minLearningRate);
            return this;
        }

        /**
         * This method defines whether model should be totally wiped out prior building, or not
         *
         * @param reallyReset
         * @return
         */
        @Override
        public Builder resetModel(boolean reallyReset) {
            super.resetModel(reallyReset);
            return this;
        }

        /**
         * This method allows to define external VocabCache to be used
         *
         * @param vocabCache
         * @return
         */
        @Override
        public Builder vocabCache(@NonNull VocabCache<VocabWord> vocabCache) {
            super.vocabCache(vocabCache);
            return this;
        }

        /**
         * This method allows to define external WeightLookupTable to be used
         *
         * @param lookupTable
         * @return
         */
        @Override
        public Builder lookupTable(@NonNull WeightLookupTable<VocabWord> lookupTable) {
            super.lookupTable(lookupTable);
            return this;
        }

        /**
         * This method defines whether subsampling should be used or not
         *
         * @param sampling set > 0 to subsampling argument, or 0 to disable
         * @return
         */
        @Override
        public Builder sampling(double sampling) {
            super.sampling(sampling);
            return this;
        }

        /**
         * This method defines whether adaptive gradients should be used or not
         *
         * @param reallyUse
         * @return
         */
        @Override
        public Builder useAdaGrad(boolean reallyUse) {
            super.useAdaGrad(reallyUse);
            return this;
        }

        /**
         * This method defines whether negative sampling should be used or not
         *
         * @param negative set > 0 as negative sampling argument, or 0 to disable
         * @return
         */
        @Override
        public Builder negativeSample(double negative) {
            super.negativeSample(negative);
            return this;
        }

        /**
         * This method defines stop words that should be ignored during training
         * @param stopList
         * @return
         */
        @Override
        public Builder stopWords(@NonNull List<String> stopList) {
            super.stopWords(stopList);
            return this;
        }

        /**
         * This method defines, if words representation should be build together with documents representations.
         *
         * @param trainElements
         * @return
         */
        @Override
        public Builder trainElementsRepresentation(boolean trainElements) {
            this.trainElementsVectors = trainElements;
            return this;
        }

        /**
         * This method is hardcoded to TRUE, since that's whole point of ParagraphVectors
         *
         * @param trainSequences
         * @return
         */
        @Override
        public Builder trainSequencesRepresentation(boolean trainSequences) {
            this.trainSequenceVectors = trainSequences;
            return this;
        }

        /**
         * This method defines stop words that should be ignored during training
         *
         * @param stopList
         * @return
         */
        @Override
        public Builder stopWords(@NonNull Collection<VocabWord> stopList) {
            super.stopWords(stopList);
            return this;
        }

        /**
         * This method defines context window size
         *
         * @param windowSize
         * @return
         */
        @Override
        public Builder windowSize(int windowSize) {
            super.windowSize(windowSize);
            return this;
        }

        /**
         * This method defines maximum number of concurrent threads available for training
         *
         * @param numWorkers
         * @return
         */
        @Override
        public Builder workers(int numWorkers) {
            super.workers(numWorkers);
            return this;
        }

        @Override
        public Builder sequenceLearningAlgorithm(SequenceLearningAlgorithm<VocabWord> algorithm) {
            super.sequenceLearningAlgorithm(algorithm);
            return this;
        }

        @Override
        public Builder sequenceLearningAlgorithm(String algorithm) {
            super.sequenceLearningAlgorithm(algorithm);
            return this;
        }

        @Override
        public Builder useHierarchicSoftmax(boolean reallyUse) {
            super.useHierarchicSoftmax(reallyUse);
            return this;
        }

        /**
         * This method has no effect for ParagraphVectors
         *
         * @param windows
         * @return
         */
        @Override
        public Builder useVariableWindow(int... windows) {
            // no-op
            return this;
        }

        @Override
        public Builder elementsLearningAlgorithm(ElementsLearningAlgorithm<VocabWord> algorithm) {
            super.elementsLearningAlgorithm(algorithm);
            return this;
        }

        @Override
        public Builder elementsLearningAlgorithm(String algorithm) {
            super.elementsLearningAlgorithm(algorithm);
            return this;
        }

        @Override
        public Builder usePreciseWeightInit(boolean reallyUse) {
            super.usePreciseWeightInit(reallyUse);
            return this;
        }

        /**
         * This method defines random seed for random numbers generator
         * @param randomSeed
         * @return
         */
        @Override
        public Builder seed(long randomSeed) {
            super.seed(randomSeed);
            return this;
        }
    }
}
